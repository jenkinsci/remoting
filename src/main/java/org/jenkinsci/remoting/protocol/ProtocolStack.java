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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.OverrideMustInvoke;
import hudson.remoting.Future;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.jcip.annotations.GuardedBy;
import net.jcip.annotations.NotThreadSafe;
import org.jenkinsci.remoting.util.ByteBufferPool;

/**
 * A stack of {@link ProtocolLayer} that make a network protocol. The stack will start with a {@link NetworkLayer}
 * instance,
 * optionally followed by a series of {@link FilterLayer} instances and terminated by a {@link ApplicationLayer}
 * instance.
 *
 * Instances are created using the {@link #on(NetworkLayer)} entry point, for example
 *
 * {@code ProtocolStack.on(netLayer).filter(filterLayer1).filter(filterLayer2).filter(filterLayer3).build(appLayer)}
 *
 * For this stack, the layers will be initialized and started in the following sequence:
 *
 * <ol>
 * <li>{@link NetworkLayer#init(ProtocolStack.Ptr)} (<em>netLayer</em>)</li>
 * <li>{@link FilterLayer#init(ProtocolStack.Ptr)} (<em>filterLayer1</em>)</li>
 * <li>{@link FilterLayer#init(ProtocolStack.Ptr)} (<em>filterLayer2</em>)</li>
 * <li>{@link FilterLayer#init(ProtocolStack.Ptr)} (<em>filterLayer3</em>)</li>
 * <li>{@link ApplicationLayer#init(ProtocolStack.Ptr)} (<em>appLayer</em>)</li>
 * <li>{@link NetworkLayer#start()} (<em>netLayer</em>)</li>
 * <li>{@link FilterLayer#start()} (<em>filterLayer1</em>)</li>
 * <li>{@link FilterLayer#start()} (<em>filterLayer2</em>)</li>
 * <li>{@link FilterLayer#start()} (<em>filterLayer3</em>)</li>
 * <li>{@link ApplicationLayer#start()} (<em>appLayer</em>)</li>
 * </ol>
 *
 * If this stack is closed via the network layer - a network initiated close - then the close will be propagated in the
 * following sequence:
 *
 * <ol>
 * <li>{@link NetworkLayer} (<em>netLayer</em>) detects close</li>
 * <li>{@link FilterLayer#onRecvClosed(IOException)} (<em>filterLayer1</em>)</li>
 * <li>{@link FilterLayer#onRecvClosed(IOException)} (<em>filterLayer2</em>)</li>
 * <li>{@link FilterLayer#onRecvClosed(IOException)} (<em>filterLayer3</em>)</li>
 * <li>{@link FilterLayer#onRecvClosed(IOException)} (<em>appLayer</em>)</li>
 * <li>{@link FilterLayer#doCloseSend()} (<em>filterLayer3</em>)</li>
 * <li>{@link FilterLayer#doCloseSend()} (<em>filterLayer2</em>)</li>
 * <li>{@link FilterLayer#doCloseSend()} (<em>filterLayer1</em>)</li>
 * <li>{@link NetworkLayer#doCloseSend()} (<em>netLayer</em>)</li>
 * </ol>
 *
 * If this stack is closed via a protocol layer - a mid-stack initiated close - then the close will be propagated in the
 * following sequence:
 *
 * <ol>
 * <li>{@link FilterLayer} (<em>filterLayer2</em>) initiates close</li>
 * <li>{@link FilterLayer#onRecvClosed(IOException)} (<em>filterLayer3</em>)</li>
 * <li>{@link FilterLayer#onRecvClosed(IOException)} (<em>appLayer</em>)</li>
 * <li>{@link FilterLayer#doCloseSend()} (<em>filterLayer3</em>)</li>
 * <li>{@link FilterLayer#doCloseSend()} (<em>filterLayer2</em>)</li>
 * <li>{@link FilterLayer#doCloseSend()} (<em>filterLayer1</em>)</li>
 * <li>{@link NetworkLayer#doCloseSend()} (<em>netLayer</em>)</li>
 * <li>{@link FilterLayer#onRecvClosed(IOException)} (<em>filterLayer1</em>)</li>
 * </ol>
 *
 * @param <T> the application specific API.
 * @since 3.0
 */
public class ProtocolStack<T> implements Closeable, ByteBufferPool {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ProtocolStack.class.getName());

    /**
     * The lock that guards the {@link Ptr#nextSend} and {@link Ptr#nextRecv} pointers.
     */
    private final ReadWriteLock stackLock = new ReentrantReadWriteLock();

    /**
     * Our network layer.
     */
    private final NetworkLayer network;

    /**
     * Our application layer.
     */
    private final ApplicationLayer<T> application;

    /**
     * The first layer for receiving data, should always point to {@link #network}.
     */
    private final Ptr recvHead;

    /**
     * The name of this stack in to provide when logging.
     */
    // TODO replace with org.slf4j.MDC once we switch to slf4j
    private String name;

    /**
     * Our listeners.
     */
    @GuardedBy("stackLock")
    private final List<Listener> listeners = new ArrayList<>();

    private final long handshakingTimeout = 10L;

    private final TimeUnit handshakingUnits = TimeUnit.SECONDS;

    private static final CountDownLatch awaitStart = new CountDownLatch(1);

    /**
     * Private constructor used by {@link Builder#build(ApplicationLayer)}
     *
     * @param name        the name of this stack to attach to logs.
     * @param network     the network transport.
     * @param filters     the filters.
     * @param application the application layer.
     */
    private ProtocolStack(
            String name,
            NetworkLayer network,
            List<FilterLayer> filters,
            ApplicationLayer<T> application,
            List<Listener> listeners) {
        this.name = name;
        this.network = network;
        this.application = application;
        this.recvHead = new Ptr(network);
        this.listeners.addAll(listeners);
        Ptr sendHead = recvHead;
        for (FilterLayer protocol : filters) {
            sendHead = new Ptr(sendHead, protocol);
        }
        new Ptr(sendHead, application);
    }

    /**
     * Create a {@link ProtocolStack} on the supplied {@link NetworkLayer}.
     *
     * @param network the {@link NetworkLayer} to build the stack on.
     * @return the {@link ProtocolStack.Builder}.
     */
    public static ProtocolStack.Builder on(NetworkLayer network) {
        return new Builder(network);
    }

    public static void waitForStart() throws InterruptedException {
        awaitStart.await();
    }

    /**
     * Initialize the stack.
     *
     * @throws IOException if the stack could not be initialized.
     */
    private void init() throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] Initializing", name());
        }
        assert recvHead.layer == network;
        for (Ptr p = recvHead; p != null; p = p.getNextRecv()) {
            p.layer.init(p);
        }
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] Starting", name());
        }
        for (Ptr p = recvHead; p != null; p = p.getNextRecv()) {
            try {
                p.layer.start();
            } catch (IOException e) {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LogRecord record = new LogRecord(Level.FINEST, "[{0}] Start failure");
                    record.setParameters(new Object[] {name()});
                    record.setThrown(e);
                    LOGGER.log(record);
                }
                Ptr nextRecv = p.getNextRecv();
                if (nextRecv != null) {
                    p.onRecvClosed(e);
                }
                throw e;
            }
        }
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] Started", name());
        }
        awaitStart.countDown();
    }

    /**
     * Gets the application specific API.
     *
     * @return the application specific API.
     * @see ApplicationLayer#get()
     */
    public T get() {
        return application.get();
    }

    /**
     * The name of this stack to use in logging.
     *
     * @return the name of this stack to use in logging.
     */
    public String name() {
        return name;
    }

    /**
     * Updates the name of this stack to use in logging.
     *
     * @param name the new name of this stack to use in logging.
     */
    public void name(String name) {
        if (!(Objects.equals(this.name, name))) {
            if (LOGGER.isLoggable(Level.FINER)) {
                LOGGER.log(Level.FINER, "[{0}] is now known as [{1}]", new Object[] {this.name, name});
            }
            this.name = name != null && !name.isEmpty() ? name : this.name;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] Closing", name());
        }
        try {
            application.doCloseWrite();
        } catch (IOException e) {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LogRecord record = new LogRecord(Level.FINEST, "[{0}] Abnormal close");
                record.setParameters(new Object[] {name()});
                record.setThrown(e);
                LOGGER.log(record);
            }
            throw e;
        } finally {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINEST, "[{0}] Closed", name());
            }
        }
    }

    /**
     * Adds a listener.
     *
     * @param listener the listener.
     */
    public void addListener(Listener listener) {
        stackLock.writeLock().lock();
        try {
            listeners.add(listener);
        } finally {
            stackLock.writeLock().unlock();
        }
    }

    /**
     * Removes a listener.
     *
     * @param listener the listener.
     */
    public void removeListener(Listener listener) {
        stackLock.writeLock().lock();
        try {
            listeners.remove(listener);
        } finally {
            stackLock.writeLock().unlock();
        }
    }

    /**
     * Request the {@link NetworkLayer} to stop receiving data.
     */
    /*package*/ void doCloseRecv() {
        network.doCloseRecv();
    }

    /**
     * Check if the {@link NetworkLayer} is open to receive data.
     *
     * @return {@code true} if the {@link NetworkLayer} is receiving data.
     */
    /*package*/ boolean isRecvOpen() {
        return network.isRecvOpen();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ProtocolStack{");
        sb.append("name='").append(name).append('\'');
        sb.append(",[");
        assert recvHead.layer == network;
        for (Ptr p = recvHead; p != null; p = p.getNextRecv()) {
            if (p != recvHead) {
                sb.append(',');
            }
            sb.append(p.layer);
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Callback notification that the protocol stack has been closed.
     *
     * @param cause the cause or {@code null} if the close was "normal".
     */
    /*package*/ void onClosed(IOException cause) {
        final List<Listener> listeners = new ArrayList<>();
        stackLock.readLock().lock();
        try {
            listeners.addAll(this.listeners);
        } finally {
            stackLock.readLock().unlock();
        }
        for (Listener listener : listeners) {
            listener.onClosed(this, cause);
        }
    }

    /**
     * Executes the given command at some time in the future.
     *
     * @param task the runnable task
     * @throws RejectedExecutionException if this task cannot be accepted for execution
     * @throws NullPointerException       if task is null
     */
    @OverrideMustInvoke
    public void execute(Runnable task) {
        network.getIoHub().execute(task);
    }

    /**
     * Executes a task at a future point in time. This method should not be used for timing critical scheduling,
     * rather it is intended to be used for things such as protocol timeouts.
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
        return network.getIoHub().executeLater(task, delay, units);
    }

    /**
     * Gets this {@link ProtocolStack}'s handshaking timeout.
     *
     * @return this {@link ProtocolStack}'s handshaking timeout.
     * @see #getHandshakingUnits()
     */
    public long getHandshakingTimeout() {
        return handshakingTimeout;
    }

    /**
     * Gets the {@link TimeUnit} of {@link #getHandshakingTimeout()}.
     *
     * @return the {@link TimeUnit} of {@link #getHandshakingTimeout()}.
     * @see #getHandshakingTimeout() ()
     */
    public TimeUnit getHandshakingUnits() {
        return handshakingUnits;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ByteBuffer acquire(int size) {
        return network.getIoHub().acquire(size);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void release(ByteBuffer buffer) {
        network.getIoHub().release(buffer);
    }

    /**
     * Builder for {@link ProtocolStack} instances.
     */
    @NotThreadSafe
    public static class Builder {

        /**
         * Used tssign each stack a unique name unless a name has been specified.
         */
        private static final AtomicInteger id = new AtomicInteger();

        /**
         * The network layer to build on.
         */
        private final NetworkLayer network;

        /**
         * The filters to build with.
         */
        private final List<FilterLayer> filters;

        /**
         * The initial listeners to register.
         */
        private final List<Listener> listeners = new ArrayList<>();

        /**
         * The name to give the protocol stack.
         */
        @CheckForNull
        private String name;

        /**
         * Flag to track that this builder has been used.
         */
        private boolean built;

        /**
         * Creates a new {@link Builder}
         *
         * @param network the network stack.
         */
        private Builder(NetworkLayer network) {
            if (network.stack() != null) {
                throw new IllegalArgumentException();
            }
            this.network = network;
            this.filters = new ArrayList<>();
        }

        /**
         * Adds the supplied filter into the {@link ProtocolStack}.
         *
         * @param filter the filter to add, if {@code null} then it will be ignored (useful for conditionally adding
         *               filters)
         * @return {@code this}.
         */
        public Builder filter(@CheckForNull FilterLayer filter) {
            if (filter != null) {
                if (filter.stack() != null) {
                    throw new IllegalArgumentException();
                }
                checkNotBuilt();
                filters.add(filter);
            }
            return this;
        }

        /**
         * Provide a custom name for the {@link ProtocolStack}.
         *
         * @param name the custom name.
         * @return {@code this}
         */
        public Builder named(String name) {
            checkNotBuilt();
            this.name = name;
            return this;
        }

        /**
         * Register a {@link Listener} for the {@link ProtocolStack}.
         *
         * @param listener the listener.
         * @return {@code this}
         */
        public Builder listener(Listener listener) {
            checkNotBuilt();
            this.listeners.add(listener);
            return this;
        }

        /**
         * Create the {@link ProtocolStack}.
         *
         * @param application the {@link ApplicationLayer} to use.
         * @param <T>         the application specific API.
         * @return the {@link ProtocolStack}.
         * @throws IOException if the {@link ProtocolStack} could not be started.
         */
        public <T> ProtocolStack<T> build(ApplicationLayer<T> application) throws IOException {
            if (application.stack() != null) {
                throw new IllegalArgumentException();
            }
            checkNotBuilt();
            built = true;
            ProtocolStack<T> stack = new ProtocolStack<>(
                    name == null || name.isEmpty() ? String.format("Stack-%d", id.incrementAndGet()) : name,
                    network,
                    filters,
                    application,
                    listeners);
            stack.init();
            return stack;
        }

        /**
         * Enforce the {@link Builder} as single use.
         */
        private void checkNotBuilt() {
            if (built) {
                throw new IllegalStateException("Builder is single-shot as Network layers cannot be reused");
            }
        }
    }

    /**
     * Tracks where a {@link ProtocolLayer} is in the {@link ProtocolStack}.
     */
    public class Ptr {
        /**
         * Our layer.
         */
        private final ProtocolLayer layer;
        /**
         * The next layer for sending.
         */
        @GuardedBy("ProtocolStack.stackLock")
        private Ptr nextSend;
        /**
         * The next layer for receiving.
         */
        @GuardedBy("ProtocolStack.stackLock")
        private Ptr nextRecv;
        /**
         * Flag to track calling {@link ProtocolLayer.Recv#onRecvClosed(IOException)}.
         */
        @GuardedBy("ProtocolStack.stackLock")
        private boolean recvOnClosed;
        /**
         * Flag to track calling {@link ProtocolLayer.Send#doCloseSend()}.
         */
        @GuardedBy("ProtocolStack.stackLock")
        private boolean sendDoClosed;
        /**
         * Flag to track this {@link ProtocolLayer} as removed from the stack.
         */
        private boolean removed;

        /**
         * Creates the first {@link Ptr}.
         *
         * @param network the {@link NetworkLayer}.
         */
        private Ptr(NetworkLayer network) {
            stackLock.writeLock().lock();
            try {
                nextSend = null;
            } finally {
                stackLock.writeLock().unlock();
            }
            this.layer = network;
        }

        /**
         * Creates a filter {@link Ptr}.
         *
         * @param nextSend the previous {@link Ptr}.
         * @param filter   the {@link FilterLayer}
         */
        private Ptr(Ptr nextSend, FilterLayer filter) {
            stackLock.writeLock().lock();
            try {
                this.nextSend = nextSend;
                nextSend.nextRecv = this;
            } finally {
                stackLock.writeLock().unlock();
            }
            this.layer = filter;
        }

        /**
         * Creates the last {@link Ptr}.
         *
         * @param nextSend    the previous {@link Ptr}.
         * @param application the {@link ApplicationLayer}
         */
        private Ptr(Ptr nextSend, ApplicationLayer<?> application) {
            stackLock.writeLock().lock();
            try {
                this.nextSend = nextSend;
                nextSend.nextRecv = this;
            } finally {
                stackLock.writeLock().unlock();
            }
            this.layer = application;
        }

        /**
         * Each {@link ProtocolLayer.Recv} should call this method to hand received data up the stack to the next
         * {@link ProtocolLayer} (except for the {@link ApplicationLayer} which should eat the data).
         *
         * @param data the data to submit to the next layer up the stack.
         * @throws IOException if the next layer could not process the data.
         */
        public void onRecv(ByteBuffer data) throws IOException {
            if (!data.hasRemaining()) {
                return;
            }
            Ptr nextRecv = getNextRecv();
            if (nextRecv == null) {
                throw new UnsupportedOperationException("Application layer is not supposed to call onRecv");
            }
            ProtocolLayer.Recv recv = (ProtocolLayer.Recv) nextRecv.layer;
            if (recv.isRecvOpen()) {
                recv.onRecv(data);
            } else {
                throw new ClosedChannelException();
            }
        }

        /**
         * Each {@link ProtocolLayer.Send} should call this method to hand data for sending down the stack to the next
         * {@link ProtocolLayer} (except for the {@link NetworkLayer} which should eat the data).
         *
         * @param data the data to submit to the next layer down the stack.
         * @throws IOException if the next layer could not process the data.
         */
        public void doSend(ByteBuffer data) throws IOException {
            if (!data.hasRemaining()) {
                return;
            }
            Ptr nextSend = getNextSend();
            if (nextSend == null) {
                throw new UnsupportedOperationException("Network layer is not supposed to call doSend");
            }
            ProtocolLayer.Send send = (ProtocolLayer.Send) nextSend.layer;
            if (send.isSendOpen()) {
                send.doSend(data);
            } else {
                throw new ClosedChannelException();
            }
        }

        /**
         * Checks if the next layer up the stack is open to receive data.
         *
         * @return {@code true} if the next layer up the stack is open to receive data.
         */
        public boolean isRecvOpen() {
            Ptr nextRecv;
            stackLock.readLock().lock();
            try {
                nextRecv = getNextRecv();
                if (nextRecv == null) {
                    throw new UnsupportedOperationException("Application layer is not supposed to call isRecvOpen");
                }
                if (recvOnClosed) {
                    return false;
                }
            } finally {
                stackLock.readLock().unlock();
            }
            return ((ProtocolLayer.Recv) nextRecv.layer).isRecvOpen();
        }

        /**
         * Checks if the next layer down the stack is open to send data.
         *
         * @return {@code true} if the next layer down the stack is open to send data.
         */
        public boolean isSendOpen() {
            Ptr nextSend;
            stackLock.readLock().lock();
            try {
                nextSend = getNextSend();
                if (nextSend == null) {
                    throw new UnsupportedOperationException("Network layer is not supposed to call isSendOpen");
                }
                if (sendDoClosed) {
                    return false;
                }
            } finally {
                stackLock.readLock().unlock();
            }
            return ((ProtocolLayer.Send) nextSend.layer).isSendOpen();
        }

        /**
         * Helper method to access the {@link ProtocolStack}.
         *
         * @return the {@link ProtocolStack}.
         */
        public ProtocolStack<?> stack() {
            return ProtocolStack.this;
        }

        /**
         * Requests removal of this {@link ProtocolLayer} from the {@link ProtocolStack}
         */
        public void remove() {
            removed = true;
        }

        /**
         * Requests the next layer down the stack to close output.
         *
         * @throws IOException if there was an error closing the output.
         */
        public void doCloseSend() throws IOException {
            if (getNextSend() == null) {
                throw new UnsupportedOperationException("Network layer is not allowed to call doClose()");
            }
            stackLock.readLock().lock();
            try {
                if (sendDoClosed) {
                    return;
                }
            } finally {
                stackLock.readLock().unlock();
            }
            stackLock.writeLock().lock();
            try {
                if (sendDoClosed) {
                    return;
                }
                sendDoClosed = true;
            } finally {
                stackLock.writeLock().unlock();
            }
            if (nextSend().isSendOpen()) {
                nextSend().doCloseSend();
            }
        }

        /**
         * Notify the next layer up the stack that input has been closed.
         *
         * @param cause the cause of the lower layer being closed or {@code null}.
         * @throws IOException if there was an error processing the close notification.
         */
        public void onRecvClosed(IOException cause) throws IOException {
            if (getNextRecv() == null) {
                throw new UnsupportedOperationException("Application layer is not supposed to call onClose");
            }
            stackLock.readLock().lock();
            try {
                if (recvOnClosed) {
                    return;
                }
            } finally {
                stackLock.readLock().unlock();
            }
            stackLock.writeLock().lock();
            try {
                if (recvOnClosed) {
                    return;
                }
                recvOnClosed = true;
            } finally {
                stackLock.writeLock().unlock();
            }
            if (nextRecv().isRecvOpen()) {
                nextRecv().onRecvClosed(cause);
            }
        }

        /**
         * Helper to get the next layer down the stack as a {@link ProtocolLayer.Send}.
         *
         * @return the next layer down the stack.
         * @throws NullPointerException if invoked from the {@link NetworkLayer}.
         */
        @NonNull
        private ProtocolLayer.Send nextSend() {
            return (ProtocolLayer.Send) getNextSend().layer;
        }

        /**
         * Gets the {@link Ptr} for the next layer down the stack (processing the send half of any intermediary
         * {@link #removed} flags if possible, if not possible it skips the removed {@link Ptr} instances anyway,
         * leaving the update to a later call)
         *
         * @return the {@link Ptr} for the next layer down the stack
         */
        @Nullable
        private Ptr getNextSend() {
            Ptr nextSend;
            stackLock.readLock().lock();
            try {
                nextSend = this.nextSend;
                while (nextSend != null && nextSend.removed && nextSend.nextSend != null) {
                    nextSend = nextSend.nextSend;
                }
                if (nextSend == this.nextSend) {
                    return nextSend;
                }
            } finally {
                stackLock.readLock().unlock();
            }
            if (stackLock.writeLock().tryLock()) {
                // we only need to unwind ourselves eventually, if we cannot do it now ok to do it later
                try {
                    while (this.nextSend != nextSend && this.nextSend != null && this.nextSend.removed) {
                        assert this.nextSend.layer instanceof FilterLayer
                                : "this is the layer before and there is a layer after nextSend thus nextSend "
                                        + "*must* be a FilterLayer";
                        ((FilterLayer) this.nextSend.layer).onSendRemoved();
                        // remove this.nextSend from the stack as it has set it's removed flag
                        Ptr tmp = this.nextSend.nextSend;
                        this.nextSend.nextSend = null;
                        this.nextSend = tmp;
                    }
                } finally {
                    stackLock.writeLock().unlock();
                }
            }
            return nextSend;
        }

        /**
         * Helper to get the next layer up the stack as a {@link ProtocolLayer.Recv}.
         *
         * @return the next layer up the stack.
         * @throws NullPointerException if invoked from the {@link ApplicationLayer}.
         */
        @NonNull
        private ProtocolLayer.Recv nextRecv() {
            return (ProtocolLayer.Recv) getNextRecv().layer;
        }

        /**
         * Gets the {@link Ptr} for the next layer up the stack (processing the receive half of any intermediary
         * {@link #removed} flags if possible, if not possible it skips the removed {@link Ptr} instances anyway,
         * leaving the update to a later call)
         *
         * @return the {@link Ptr} for the next layer up the stack
         */
        @Nullable
        private Ptr getNextRecv() {
            Ptr nextRecv;
            stackLock.readLock().lock();
            try {
                nextRecv = this.nextRecv;
                while (nextRecv != null && nextRecv.removed && nextRecv.nextRecv != null) {
                    nextRecv = nextRecv.nextRecv;
                }
                if (nextRecv == this.nextRecv) {
                    return this.nextRecv;
                }
            } finally {
                stackLock.readLock().unlock();
            }
            if (stackLock.writeLock().tryLock()) {
                // we only need to unwind ourselves eventually, if we cannot do it now ok to do it later
                try {
                    while (this.nextRecv != nextRecv && this.nextRecv != null && this.nextRecv.removed) {
                        assert this.nextRecv.layer instanceof FilterLayer
                                : "this is the layer before and there is a layer after nextRecv thus nextRecv "
                                        + "*must* be a FilterLayer";
                        ((FilterLayer) this.nextRecv.layer).onRecvRemoved();
                        // remove this.nextRecv from the stack as it has set it's removed flag
                        Ptr tmp = this.nextRecv.nextRecv;
                        this.nextRecv.nextRecv = null;
                        this.nextRecv = tmp;
                    }
                } finally {
                    stackLock.writeLock().unlock();
                }
            }
            return nextRecv;
        }
    }

    /**
     * Callback "interface" for changes in the state of {@link ProtocolStack}.
     */
    public interface Listener {
        /**
         * When the stack was closed normally or abnormally due to an error.
         *
         * @param stack the stack that has closed.
         * @param cause if the stack is closed abnormally, this parameter
         *              represents an exception that has triggered it.
         *              Otherwise {@code null}.
         */
        void onClosed(ProtocolStack<?> stack, IOException cause);
    }
}
