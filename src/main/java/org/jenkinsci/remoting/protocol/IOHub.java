/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc., Stephen Connolly
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.remoting.protocol;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.OverrideMustInvoke;
import hudson.remoting.Future;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.jcip.annotations.GuardedBy;
import org.jenkinsci.remoting.util.ByteBufferPool;
import org.jenkinsci.remoting.util.DirectByteBufferPool;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A hub for performing I/O. The hub has a selector thread and an executor service.
 *
 * @since 3.0
 */
public class IOHub implements Executor, Closeable, Runnable, ByteBufferPool {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(IOHub.class.getName());

    /**
     * Defines the Selector wakeup timeout via a system property. Defaults to {@code 1000ms}.
     * @since 3.15
     */
    private static final long SELECTOR_WAKEUP_TIMEOUT_MS =
            Long.getLong(IOHub.class.getName() + ".selectorWakeupTimeout", 1000);

    /**
     * The next ID to use.
     */
    private static final AtomicInteger nextId = new AtomicInteger(1);
    /**
     * Our ID.
     */
    private final int _id = nextId.getAndIncrement();
    /**
     * Our selector.
     */
    private final Selector selector;

    private volatile boolean ioHubRunning = false;
    private final Object selectorLockObject = new Object();

    /**
     * Our executor.
     */
    private final Executor executor;

    /**
     * The scheduled tasks to run later.
     */
    private final DelayQueue<DelayedRunnable> scheduledTasks = new DelayQueue<>();
    /**
     * Tasks to run on the selector thread.
     */
    private final Queue<Runnable> selectorTasks = new ConcurrentLinkedQueue<>();
    /**
     * Registrations to process (these must take place on the selector thread). We could process these using
     * a {@link Runnable} on {@link #selectorTasks} but we want to optimize detecting when to call
     * {@link Selector#selectNow()}.
     */
    private final Queue<Registration> registrations = new ConcurrentLinkedQueue<>();
    /**
     * {@link SelectionKey#interestOps()} modifications to process (these are safer taking place on the selector
     * thread).We could process these using a {@link Runnable} on {@link #selectorTasks} but we want to optimize
     * detecting when to call {@link Selector#selectNow()}.
     */
    private final Queue<InterestOps> interestOps = new ConcurrentLinkedQueue<>();
    /**
     * Counts the # of select loops. Ocassionally useful for diagnosing whether the selector
     * thread is spending too much CPU time.
     */
    private long gen;
    /**
     * Our {@link ByteBufferPool}.
     */
    private final ByteBufferPool bufferPool;

    /**
     * Creates a new {@link IOHub} instance.
     *
     * @param executor the {@link Executor} to use for running tasks.
     * @throws IOException if the hub's {@link Selector} cannot be opened.
     */
    private IOHub(Executor executor) throws IOException {
        this.selector = Selector.open();
        this.ioHubRunning = true;
        this.executor = executor;
        this.bufferPool = new DirectByteBufferPool(16916, Runtime.getRuntime().availableProcessors() * 4);
    }

    /**
     * Creates and starts a new {@link IOHub} instance.
     *
     * @param executor the {@link Executor} to use for running tasks.
     * @return the new hub.
     * @throws IOException if the hub's {@link Selector} cannot be opened.
     */
    public static IOHub create(Executor executor) throws IOException {
        IOHub result = new IOHub(executor);
        executor.execute(result);
        LOGGER.log(
                Level.FINE, "Starting an additional Selector wakeup thread. See JENKINS-47965 for more information.");
        executor.execute(new IOHubSelectorWatcher(result));
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ByteBuffer acquire(int size) {
        return bufferPool.acquire(size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void release(ByteBuffer buffer) {
        bufferPool.release(buffer);
    }

    /**
     * Returns the {@link Selector}.
     *
     * @return the {@link Selector}
     */
    @NonNull
    public final Selector getSelector() {
        return selector;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @OverrideMustInvoke
    public void execute(@NonNull Runnable task) {
        executor.execute(task);
    }

    /**
     * Executes the given task at some time in the future.  The task
     * will execute in the selector thread.
     *
     * @param task the runnable task
     * @throws RejectedExecutionException if this task cannot be accepted for execution
     * @throws NullPointerException       if task is null
     */
    @OverrideMustInvoke
    public void executeOnSelector(Runnable task) {
        if (task == null) {
            throw new NullPointerException("Task is null");
        }
        if (!selector.isOpen()) {
            throw new RejectedExecutionException("IOHub#" + _id + " Selector is closed");
        }
        try {
            selectorTasks.add(task);
        } catch (IllegalStateException e) {
            throw new RejectedExecutionException("IOHub#" + _id + "Selector task list is full", e);
        }
        selector.wakeup();
    }

    /**
     * Executes a task at a future point in time. The scheduling is handled by the selector thread, and as such
     * this method should not be used for timing critical scheduling, rather it is intended to be used for
     * things such as protocol timeouts.
     *
     * @param task  the task.
     * @param delay the delay.
     * @param units the time units for the delay.
     * @return a {@link Future} that completes when the task has run and can be used to cancel the execution.
     * @throws RejectedExecutionException if this task cannot be accepted for execution
     * @throws NullPointerException       if task is null
     */
    @OverrideMustInvoke
    public Future<?> executeLater(Runnable task, long delay, TimeUnit units) {
        if (task == null) {
            throw new NullPointerException("Task is null");
        }
        if (!selector.isOpen()) {
            throw new RejectedExecutionException("IOHub#" + _id + " Selector is closed");
        }
        DelayedRunnable future = new DelayedRunnable(task, delay, units);
        scheduledTasks.add(future);
        return future;
    }

    /**
     * Check if the hub is open.
     *
     * @return {@code true} if the hub is open.
     */
    @OverrideMustInvoke
    public boolean isOpen() {
        return selector.isOpen();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @OverrideMustInvoke
    public void close() throws IOException {
        selector.close();
    }

    /**
     * Reregister the provided key as interested in accepting connections.
     *
     * @param key the key.
     */
    public final void addInterestAccept(SelectionKey key) {
        interestOps.add(new InterestOps(key, SelectionKey.OP_ACCEPT, 0));
        selector.wakeup();
    }

    /**
     * Reregister the provided key as no longer interested in accepting connections.
     *
     * @param key the key.
     */
    public final void removeInterestAccept(SelectionKey key) {
        interestOps.add(new InterestOps(key, 0, SelectionKey.OP_ACCEPT));
        selector.wakeup();
    }

    /**
     * Reregister the provided key as interested in the connection with a server being established.
     *
     * @param key the key.
     */
    public final void addInterestConnect(SelectionKey key) {
        interestOps.add(new InterestOps(key, SelectionKey.OP_CONNECT, 0));
        selector.wakeup();
    }

    /**
     * Reregister the provided key as no longer interested in the connection with a server being established.
     *
     * @param key the key.
     */
    public final void removeInterestConnect(SelectionKey key) {
        interestOps.add(new InterestOps(key, 0, SelectionKey.OP_CONNECT));
        selector.wakeup();
    }

    /**
     * Reregister the provided key as interested in reading data.
     *
     * @param key the key.
     */
    public final void addInterestRead(SelectionKey key) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            // TODO probably want some more info about the key here...
            LOGGER.log(Level.FINEST, "Scheduling adding OP_READ to {0}", key);
        }
        interestOps.add(new InterestOps(key, SelectionKey.OP_READ, 0));
        selector.wakeup();
    }

    /**
     * Reregister the provided key as no longer interested in reading data.
     *
     * @param key the key.
     */
    public final void removeInterestRead(SelectionKey key) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            // TODO probably want some more info about the key here...
            LOGGER.log(Level.FINEST, "Scheduling removing OP_READ to {0}", key);
        }
        interestOps.add(new InterestOps(key, 0, SelectionKey.OP_READ));
        selector.wakeup();
    }

    /**
     * Reregister the provided key as interested in writing data.
     *
     * @param key the key.
     */
    public final void addInterestWrite(SelectionKey key) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            // TODO probably want some more info about the key here...
            LOGGER.log(Level.FINEST, "Scheduling adding OP_WRITE to {0}", key);
        }
        interestOps.add(new InterestOps(key, SelectionKey.OP_WRITE, 0));
        selector.wakeup();
    }

    /**
     * Reregister the provided key as no longer interested in writing data.
     *
     * @param key the key.
     */
    public final void removeInterestWrite(SelectionKey key) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            // TODO probably want some more info about the key here...
            LOGGER.log(Level.FINEST, "Scheduling removing OP_WRITE to {0}", key);
        }
        interestOps.add(new InterestOps(key, 0, SelectionKey.OP_WRITE));
        selector.wakeup();
    }

    /**
     * Register the {@link SelectableChannel} for the requested operations using the supplied
     * {@link IOHubReadyListener}, when the registration is complete the {@link IOHubRegistrationCallback} will be
     * invoked.
     *
     * @param channel  the {@link SelectableChannel} to register.
     * @param listener the {@link IOHubReadyListener} to call when the requested operations are available.
     * @param accept   {@code true} to initially register for accepting connections from clients.
     * @param connect  {@code true} to initially register for connection established with server.
     * @param read     {@code true} to initially register for reading data.
     * @param write    {@code true} to initially register for writing data.
     * @param callback the {@link IOHubRegistrationCallback} to notify on registration.
     */
    public final void register(
            SelectableChannel channel,
            IOHubReadyListener listener,
            boolean accept,
            boolean connect,
            boolean read,
            boolean write,
            IOHubRegistrationCallback callback) {
        int ops = 0;
        if (accept) {
            ops |= SelectionKey.OP_ACCEPT;
        }
        if (connect) {
            ops |= SelectionKey.OP_CONNECT;
        }
        if (read) {
            ops |= SelectionKey.OP_READ;
        }
        if (write) {
            ops |= SelectionKey.OP_WRITE;
        }
        registrations.add(new Registration(ops, channel, listener, callback));
        selector.wakeup();
    }

    /**
     * Register the {@link SelectableChannel} for the requested operations using the supplied
     * {@link IOHubReadyListener}.
     *
     * @param channel  the {@link SelectableChannel} to register.
     * @param listener the {@link IOHubReadyListener} to call when the requested operations are available.
     * @param accept   {@code true} to initially register for accepting connections from clients.
     * @param connect  {@code true} to initially register for connection established with server.
     * @param read     {@code true} to initially register for reading data.
     * @param write    {@code true} to initially register for writing data.
     * @return the {@link Future} for the {@link SelectionKey}.
     */
    public final Future<SelectionKey> register(
            SelectableChannel channel,
            IOHubReadyListener listener,
            boolean accept,
            boolean connect,
            boolean read,
            boolean write) {
        IOHubRegistrationFutureAdapterImpl callback = new IOHubRegistrationFutureAdapterImpl();
        register(channel, listener, accept, connect, read, write, callback);
        return callback.getFuture();
    }

    /**
     * Removes the {@link SelectableChannel} from the hub's {@link Selector}.
     *
     * @param channel the {@link SelectableChannel} to remove.
     */
    public final void unregister(SelectableChannel channel) {
        SelectionKey selectionKey = channel.keyFor(selector);
        if (selectionKey == null) {
            return;
        }
        selectionKey.cancel();
        selectionKey.attach(null);
    }

    private String getThreadNameBase(String executorThreadName) {
        int keySize;
        try {
            keySize = selector.keys().size();
        } catch (ClosedSelectorException x) {
            keySize = -1; // possibly a race condition, ignore
        }
        return "IOHub#" + _id + ": Selector[keys:" + keySize + ", gen:" + gen + "] / " + executorThreadName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Restricted(NoExternalUse.class)
    public final void run() {
        Thread selectorThread = Thread.currentThread();
        String oldName = selectorThread.getName();
        long cpuOverheatProtection = System.nanoTime();
        try {
            while (isOpen()) {
                selectorThread.setName(getThreadNameBase(oldName));
                try {
                    processScheduledTasks();
                    boolean wantSelectNow = processRegistrations();
                    wantSelectNow = processInterestOps() || wantSelectNow;
                    wantSelectNow = processSelectorTasks() || wantSelectNow;
                    int selected;
                    if (wantSelectNow) {
                        // we did some work that is anticipated to either take some time or have likely resulted
                        // in an immediately ready selection key, hence we use the non-blocking form
                        selected = selector.selectNow();
                    } else {
                        // On Windows the select(timeout) operation ALWAYS waits for the timeout,
                        // so we workaround it by IOHubSelectorWatcher
                        // "Ubuntu on Windows also qualifies as Windows, so we just rely on the wakeup thread ad use
                        // infinite timeout"
                        selected = selector.select();
                    }

                    if (selected == 0) {
                        // don't stress the GC by creating instantiating the selected keys
                        continue;
                    }
                    Set<SelectionKey> keys = selector.selectedKeys();
                    gen++;
                    for (Iterator<SelectionKey> keyIterator = keys.iterator(); keyIterator.hasNext(); ) {
                        SelectionKey key = keyIterator.next();
                        if (key.isValid()) {
                            try {
                                final int ops = key.readyOps();
                                key.interestOps(key.interestOps() & ~ops);
                                final IOHubReadyListener listener = (IOHubReadyListener) key.attachment();
                                if (listener != null) {
                                    execute(new OnReady(_id, key, listener, ops));
                                }
                            } catch (CancelledKeyException e) {
                                // ignore, we have guarded against with the call to SelectionKey.isValid()
                            }
                        }
                        keyIterator.remove();
                    }
                } catch (IOException e) {
                    // we should not have any of these exceptions propagated this far, so if we get one that is a
                    // problem

                    LOGGER.log(Level.WARNING, "Unexpected selector thread exception", e);
                    long sleepNanos = System.nanoTime() - cpuOverheatProtection;
                    if (sleepNanos > 0) {
                        if (LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.log(
                                    Level.FINEST,
                                    "Sleeping for {0,number}ns to prevent selector thread CPU monopolization!",
                                    sleepNanos);
                        }
                        try {
                            TimeUnit.NANOSECONDS.sleep(sleepNanos);
                        } catch (InterruptedException ignored) {
                            // ignore
                        }
                    } else {
                        // if we get lots of these exceptions in a row, that is a problem and we may well be stealing
                        // CPU time from whatever else may be able to fix things, so let's draw a marker in the sand
                        // if we catch another propagated exception in the next short while then we should just sleep
                        // before looping again. For now we will just yield as that is likely enough for most simple
                        // cases.
                        cpuOverheatProtection = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
                        Thread.yield();
                    }
                }
            }
        } catch (ClosedSelectorException e) {
            // ignore, happens routinely
        } finally {
            selectorThread.setName(oldName);
            ioHubRunning = false;
            synchronized (selectorLockObject) {
                selectorLockObject.notifyAll();
            }
        }
    }

    /**
     * This is an artificial thread, which monitors IOHub Selector and wakes it up if it waits for more than 1 second.
     * It is a workaround for Selector#select(long timeout) on Windows, where the call always waits for the entire timeout before returning back.
     * Since the same behavior happens on Unix emulation layer in Windows, we run this thread on Unix platforms as well.
     */
    private static class IOHubSelectorWatcher implements Runnable {

        private final IOHub iohub;

        public IOHubSelectorWatcher(IOHub iohub) {
            this.iohub = iohub;
        }

        @Override
        public void run() {
            final Thread watcherThread = Thread.currentThread();
            final String oldName = watcherThread.getName();
            final String watcherName = "Windows IOHub Watcher for " + iohub.getThreadNameBase(oldName);
            LOGGER.log(Level.FINEST, "{0}: Started", watcherName);
            try {
                watcherThread.setName(watcherName);
                while (true) {
                    synchronized (iohub.selectorLockObject) {
                        if (iohub.ioHubRunning) {
                            iohub.selectorLockObject.wait(SELECTOR_WAKEUP_TIMEOUT_MS);
                        } else {
                            break;
                        }
                    }
                    iohub.selector.wakeup();
                }
            } catch (InterruptedException ex) {
                // interrupted
                LOGGER.log(Level.FINE, "Interrupted", ex);
            } finally {
                watcherThread.setName(oldName);
                LOGGER.log(Level.FINEST, "{0}: Finished", watcherName);
            }
        }
    }

    /**
     * Process the scheduled tasks list.
     */
    private void processScheduledTasks() {
        final int tasksWaiting = scheduledTasks.size();
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "{0} scheduled tasks to process", tasksWaiting);
        }
        if (tasksWaiting > 4) {
            // DelayQueue.drainTo is more efficient than repeated polling
            // but we don't want to create the ArrayList every time the selector loops
            List<DelayedRunnable> scheduledWork = new ArrayList<>();
            scheduledTasks.drainTo(scheduledWork);
            for (DelayedRunnable task : scheduledWork) {
                if (!task.isCancelled()) {
                    execute(task);
                }
            }
        } else {
            // in the majority of cases we expect maybe one task to be active
            // as in most cases we will not be handshaking more than one or two connections
            // at a time, so let's give that a path that doesn't introduce GC pressure
            for (DelayedRunnable task = scheduledTasks.poll(); task != null; task = scheduledTasks.poll()) {
                if (!task.isCancelled()) {
                    execute(task);
                }
            }
        }
    }

    /**
     * Process the registration list.
     *
     * @return {@code true} if something was processed.
     */
    private boolean processRegistrations() {
        boolean processedSomething = false;
        for (Registration r = registrations.poll(); r != null; r = registrations.poll()) {
            try {
                SelectionKey selectionKey = r.channel.register(selector, r.ops, r.listener);
                processedSomething = true;
                r.callback.onRegistered(selectionKey);
            } catch (ClosedChannelException e) {
                r.callback.onClosedChannel(e);
            }
        }
        return processedSomething;
    }

    /**
     * Process the {@link SelectionKey#interestOps(int)} modifications.
     *
     * @return {@code true} if something was processed.
     */
    private boolean processInterestOps() {
        boolean processedSomething = false;
        for (InterestOps ops = interestOps.poll(); ops != null; ops = interestOps.poll()) {
            try {
                if (ops.interestOps()) {
                    processedSomething = true;
                }
            } catch (CancelledKeyException e) {
                // ignore
            }
        }
        return processedSomething;
    }

    /**
     * Process the tasks that have to run on the selector thread.
     *
     * @return {@code true} if something was processed.
     */
    private boolean processSelectorTasks() {
        boolean processedSomething = false;
        for (Runnable task = selectorTasks.poll(); task != null; task = selectorTasks.poll()) {
            processedSomething = true;
            task.run();
        }
        return processedSomething;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        IOHub ioHub = (IOHub) o;

        return _id == ioHub._id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return _id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("IOHub#");
        sb.append(_id);
        if (selector.isOpen()) {
            sb.append("[open, keys=").append(selector.keys().size());
        } else {
            sb.append("[closed");
        }
        sb.append(", gen=").append(gen);
        sb.append(']');
        return sb.toString();
    }

    /**
     * Track registration requests.
     */
    private static final class Registration {
        /**
         * The initial ops.
         */
        private final int ops;

        /**
         * The channel to register.
         */
        private final SelectableChannel channel;

        /**
         * The listener to use as the {@link SelectionKey#attachment()}.
         */
        private final IOHubReadyListener listener;

        /**
         * The callback to notify on registration.
         */
        private final IOHubRegistrationCallback callback;

        /**
         * Constructor.
         *
         * @param ops      the initial ops.
         * @param channel  the channel to register.
         * @param listener the listener to set as the {@link SelectionKey#attachment()}.
         * @param callback the callback to notify on registration.
         */
        Registration(
                int ops, SelectableChannel channel, IOHubReadyListener listener, IOHubRegistrationCallback callback) {
            this.ops = ops;
            this.channel = channel;
            this.listener = listener;
            this.callback = callback;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            return "Registration{" + "ops=" + ops + ", channel="
                    + channel + ", listener="
                    + listener + ", callback="
                    + callback + '}';
        }
    }

    /**
     * Task to handle the {@link IOHubReadyListener#ready(boolean, boolean, boolean, boolean)} notification.
     */
    private static final class OnReady implements Runnable {
        /**
         * The {@link IOHub#_id}
         */
        private final int _id;
        /**
         * The key
         */
        private final SelectionKey key;
        /**
         * The listener.
         */
        private final IOHubReadyListener listener;
        /**
         * The ready ops.
         */
        private final int ops;

        OnReady(int _id, SelectionKey key, IOHubReadyListener listener, int ops) {
            this._id = _id;
            this.key = key;
            this.listener = listener;
            this.ops = ops;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            final Thread workerThread = Thread.currentThread();
            final String oldName = workerThread.getName();
            try {
                workerThread.setName("IOHub#" + _id + ": Worker[channel:" + key.channel() + "] / " + oldName);
                if (LOGGER.isLoggable(Level.FINEST)) {
                    // TODO probably want some more info about the key here...
                    LOGGER.log(
                            Level.FINEST, "Calling listener.ready({0}, {1}, {2}, {3}) for channel {4}", new Object[] {
                                (ops & SelectionKey.OP_ACCEPT) == SelectionKey.OP_ACCEPT,
                                (ops & SelectionKey.OP_CONNECT) == SelectionKey.OP_CONNECT,
                                (ops & SelectionKey.OP_READ) == SelectionKey.OP_READ,
                                (ops & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE,
                                key.channel()
                            });
                }
                listener.ready(
                        (ops & SelectionKey.OP_ACCEPT) == SelectionKey.OP_ACCEPT,
                        (ops & SelectionKey.OP_CONNECT) == SelectionKey.OP_CONNECT,
                        (ops & SelectionKey.OP_READ) == SelectionKey.OP_READ,
                        (ops & SelectionKey.OP_WRITE) == SelectionKey.OP_WRITE);
            } catch (Throwable e) {
                if (LOGGER.isLoggable(Level.SEVERE)) {
                    LogRecord record = new LogRecord(Level.SEVERE, "[{0}] Listener {1} propagated an uncaught {2}");
                    record.setThrown(e);
                    record.setParameters(new Object[] {
                        workerThread.getName(), listener, e.getClass().getSimpleName()
                    });
                    LOGGER.log(record);
                }
                if (e instanceof Error) {
                    throw (Error) e;
                }
            } finally {
                workerThread.setName(oldName);
            }
        }
    }

    /**
     * Base class for {@link SelectionKey#interestOps()} modification requests.
     */
    private static final class InterestOps {
        /**
         * The {@link SelectionKey}.
         */
        private final SelectionKey key;

        /**
         * The mask to AND against the ops with.
         */
        private final int opsAnd;
        /**
         * The mask to OR against the ops with.
         */
        private final int opsOr;

        /**
         * Constructor.
         *
         * @param key    the selection key.
         * @param add    the ops bits to add
         * @param remove the ops bits to remove.
         */
        private InterestOps(SelectionKey key, int add, int remove) {
            this.key = key;
            this.opsAnd = ~remove;
            this.opsOr = add;
        }

        /**
         * Returns {@code true} if the desired {@link SelectionKey#interestOps()} was potentially updated.
         * This method will generally return {@code false} only if the {@link SelectionKey} is no longer valid when the request runs.
         *
         * @return {@code true} if the desired {@link SelectionKey#interestOps()} was potentially modified.
         */
        private boolean interestOps() {
            if (LOGGER.isLoggable(Level.FINEST)) {
                // TODO probably want some more info about the key here...
                LOGGER.log(
                        Level.FINEST,
                        "updating interest ops &={0} |={1} on {2} with existing ops {3} on key {4}",
                        new Object[] {opsAnd, opsOr, key.channel(), key.interestOps(), key});
            }
            if (key.isValid()) {
                key.interestOps((key.interestOps() & opsAnd) | opsOr);
                return true;
            }
            return false;
        }
    }

    /**
     * A scheduled task for {@link IOHub#scheduledTasks}. While it would be fun to have this class implement nanosecond
     * precision using {@link AbstractQueuedSynchronizer} the use case is network timeouts which will typically be of
     * the order of multiple seconds so the simpler implementation using intrinsic locks and
     * {@link System#currentTimeMillis()} is appropriate.
     */
    private final class DelayedRunnable implements Runnable, Delayed, Future<Void> {

        /**
         * The task to run or {@code null} if the task has been cancelled.
         */
        @GuardedBy("this")
        private Runnable task;
        /**
         * Any exceptional failure of the task.
         */
        @GuardedBy("this")
        private Throwable failure;
        /**
         * The {@link System#currentTimeMillis()} after which the task should be executed.
         */
        private final long delayTime;
        /**
         * Flag to track completion of the task.
         */
        @GuardedBy("this")
        private boolean done;

        /**
         * Constructor.
         *
         * @param task  the task.
         * @param delay the delay.
         * @param unit  the delay units.
         */
        private DelayedRunnable(Runnable task, long delay, TimeUnit unit) {
            this.task = task;
            this.delayTime = System.currentTimeMillis() + unit.toMillis(delay);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized long getDelay(@NonNull TimeUnit unit) {
            return task == null
                    ? Long.MIN_VALUE
                    : unit.convert(delayTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized int compareTo(Delayed o) {
            // we want to compare based on the delay
            long x = getDelay(TimeUnit.NANOSECONDS);
            long y = o.getDelay(TimeUnit.NANOSECONDS);
            return Long.compare(x, y);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            // we want identity based equality
            return super.hashCode();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object obj) {
            // we want identity based equality
            return super.equals(obj);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            Runnable task;
            synchronized (this) {
                task = this.task;
            }
            if (task != null) {
                final Thread workerThread = Thread.currentThread();
                final String oldName = workerThread.getName();
                try {
                    workerThread.setName(String.format("IOHub#%d: Timeout[%s] / %s", _id, task, oldName));
                    task.run();
                    synchronized (this) {
                        done = true;
                        notifyAll();
                    }
                } catch (Throwable t) {
                    synchronized (this) {
                        failure = t;
                        done = true;
                        notifyAll();
                    }
                } finally {
                    workerThread.setName(oldName);
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            if (done) {
                return false;
            }
            task = null;
            notifyAll();
            return true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized boolean isCancelled() {
            return task == null;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized boolean isDone() {
            return done;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized Void get() throws InterruptedException, ExecutionException {
            while (!done) {
                if (!IOHub.this.isOpen()) {
                    throw new CancellationException("IOHub#" + _id + " Selector is closed");
                }
                if (task == null) {
                    throw new CancellationException();
                }
                // do not block for more than 30 seconds as we need to periodically check that the
                // hub is still open.
                long remaining = Math.min(30000, delayTime - System.currentTimeMillis());
                // wait for at least 1 second as the selector thread can block that long if idle
                wait(Math.max(1000, remaining));
            }
            if (failure != null) {
                throw new ExecutionException(failure);
            }
            return null;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized Void get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            long giveUp = System.currentTimeMillis() + unit.toMillis(timeout);
            while (!done) {
                if (!IOHub.this.isOpen()) {
                    throw new CancellationException("IOHub#" + _id + " Selector is closed");
                }
                long timeoutin = giveUp - System.currentTimeMillis();
                if (timeoutin <= 0) {
                    throw new TimeoutException();
                }
                if (task == null) {
                    throw new CancellationException();
                }
                // do not block for more than 30 seconds as we need to periodically check that the
                // hub is still open.
                long remaining = Math.min(30000, delayTime - System.currentTimeMillis());
                // wait for at least 1 second or the remaining timeout as the selector thread can block that long if
                // idle
                wait(Math.min(timeoutin, Math.max(1000, remaining)));
            }
            if (failure != null) {
                throw new ExecutionException(failure);
            }
            return null;
        }
    }
}
