package org.jenkinsci.remoting.nio;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.AbstractByteArrayCommandTransport;
import hudson.remoting.Callable;
import hudson.remoting.Capability;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.ChunkHeader;
import hudson.remoting.CommandTransport;
import hudson.remoting.SingleLaneExecutorService;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.NotSerializableException;
import java.io.ObjectStreamException;
import java.io.OutputStream;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.RoleChecker;
import org.jenkinsci.remoting.util.ExecutorServiceUtils;

/**
 * Switch board of multiple {@link Channel}s through NIO select.
 *
 * Through this hub, N threads can attend to M channels with a help of one selector thread.
 *
 * <p>
 * To get the selector thread going, call the {@link #run()} method from a thread after you instantiate this object.
 * The {@link #run()} method will block until the hub gets closed.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.38
 */
public class NioChannelHub implements Runnable, Closeable {
    private final Selector selector;
    /**
     * Maximum size of the chunk.
     */
    private int transportFrameSize = 8192;

    private final SelectableFileChannelFactory factory = new SelectableFileChannelFactory();

    /**
     * Used to schedule work that can be only done synchronously with the {@link Selector#select()} call.
     */
    private final Queue<Callable<Void, IOException>> selectorTasks = new ConcurrentLinkedQueue<>();

    /**
     * {@link ExecutorService} that processes command parsing and executions.
     */
    private final ExecutorService commandProcessor;

    /**
     * Counts the # of select loops. Ocassionally useful for diagnosing whether the selector
     * thread is spending too much CPU time.
     */
    private long gen;

    /**
     * Sets to the thread that's in the {@link #run()} method.
     */
    private volatile Thread selectorThread;

    private volatile Throwable whatKilledSelectorThread;

    // used to ensure that NioChannelHub.run() has started before creating new channels
    private boolean started = false;
    private final Object startedLock = new Object();

    /**
     * Bi-directional NIO channel used as the transport of a {@link Channel}.
     *
     * <p>
     * The read end of it has to be a {@link Channel} that is both selectable and readable.
     * There's no single type that captures this, so we rely on {@link #rr()} and {@link #ww()} to convey this idea.
     *
     * <p>
     * Sometimes a single NIO channel object does both read and write, like {@link SocketChannel}.
     * In other times, two channel objects are used to do read and write each.
     * {@link MonoNioTransport} and {@link DualNioTransport} subtypes handle these differences.
     */
    abstract class NioTransport extends AbstractByteArrayCommandTransport {
        private final Capability remoteCapability;

        /**
         * Where we pools bytes read from {@link #rr()} but not yet passed to {@link AbstractByteArrayCommandTransport.ByteArrayReceiver}.
         *
         * The receiver buffer has to be big enough to accommodate a single command in its entirety.
         * There's no size restriction in a command, so we'll just buffer as much as we can.
         */
        final FifoBuffer rb = new FifoBuffer(16 * 1024, Integer.MAX_VALUE);
        /**
         * Where we pools bytes to be send to {@link #ww()} but not yet done.
         */
        final FifoBuffer wb = new FifoBuffer(16 * 1024, 256 * 1024);

        @CheckForNull
        private AbstractByteArrayCommandTransport.ByteArrayReceiver receiver = null;

        /**
         * To ensure serial execution order within each {@link Channel}, we submit
         * received packets through a per-{@link NioTransport} swim lane.
         */
        private final SingleLaneExecutorService swimLane = new SingleLaneExecutorService(commandProcessor);

        /**
         * Name given to the transport to assist trouble-shooting.
         */
        private final String name;

        NioTransport(String name, Capability remoteCapability) {
            this.name = name;
            this.remoteCapability = remoteCapability;
        }

        abstract ReadableByteChannel rr();

        abstract WritableByteChannel ww();

        /**
         * Based on the state of this {@link NioTransport}, register NIO channels to the selector.
         *
         * This methods must run in the selector thread.
         */
        @SelectorThreadOnly
        public abstract void reregister() throws IOException;

        /**
         * Returns true if we want to read from {@link #rr()}, namely
         * when we have more space in {@link #rb}.
         */
        boolean wantsToRead() {
            return receiver != null && rb.writable() != 0;
        }

        /**
         * Returns true if we want to write to {@link #ww()}, namely
         * when we have some data in {@link #wb}.
         */
        boolean wantsToWrite() {
            return wb.readable() != 0;
        }

        /**
         * Is the write end of the NIO channel still open?
         */
        abstract boolean isWopen();

        /**
         * Is the read end of the NIO channel still open?
         */
        abstract boolean isRopen();

        /**
         * Closes the read end of the NIO channel.
         *
         * Client isn't allowed to call {@link java.nio.channels.Channel#close()} on {@link #rr()}.
         * Call this method instead.
         */
        @SelectorThreadOnly
        abstract void closeR() throws IOException;

        /**
         * The Write end version of {@link #closeR()}.
         */
        @SelectorThreadOnly
        abstract void closeW() throws IOException;

        @SelectorThreadOnly
        protected final void cancelKey(SelectionKey key) {
            if (key != null) {
                key.cancel();
            }
        }

        protected Channel getChannel() {
            return channel;
        }

        // TODO: do not just ignore the exceptions below
        @SelectorThreadOnly
        public void abort(Throwable e) {
            try {
                closeR();
            } catch (IOException ignored) {
                // ignore
            }
            try {
                closeW();
            } catch (IOException ignored) {
                // ignore
            }
            if (receiver == null) {
                throw new IllegalStateException("Aborting connection before it has been actually set up");
            }
            receiver.terminate(new IOException("Connection aborted: " + this, e));
        }

        @Override
        public void writeBlock(Channel channel, byte[] bytes) throws IOException {
            try {
                boolean hasMore;
                int pos = 0;
                do {
                    int frame = Math.min(transportFrameSize, bytes.length - pos); // # of bytes we send in this chunk
                    hasMore = frame + pos < bytes.length;
                    wb.write(ChunkHeader.pack(frame, hasMore));
                    wb.write(bytes, pos, frame);
                    scheduleReregister();
                    pos += frame;
                } while (hasMore);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw (InterruptedIOException) new InterruptedIOException().initCause(e);
            }
        }

        @Override
        public void setup(AbstractByteArrayCommandTransport.ByteArrayReceiver receiver) {
            this.receiver = receiver;
            scheduleReregister(); // ready to read bytes now
        }

        @Override
        public Capability getRemoteCapability() throws IOException {
            return remoteCapability;
        }

        @Override
        public void closeWrite() throws IOException {
            wb.close();
            // when wb is fully drained and written, we'll call closeW()
        }

        @Override
        public void closeRead() throws IOException {
            scheduleSelectorTask(() -> {
                closeR();
                return null;
            });
        }

        /**
         * Update the operations for which we are registered.
         */
        private void scheduleReregister() {
            scheduleSelectorTask(() -> {
                reregister();
                return null;
            });
        }

        @Override
        public String toString() {
            return super.toString() + "[name=" + name + "]";
        }
    }

    /**
     * NioTransport that uses a single {@link SelectableChannel} to do both read and write.
     */
    class MonoNioTransport extends NioTransport {
        private final SelectableChannel ch;
        /**
         * To close read and write end independently, we need to do half-close, which goes beyond
         * the contract of {@link SelectableChannel}. These objects represent the strategy to close them,
         * and when it's closed, set to null.
         */
        Closeable rc, wc;

        MonoNioTransport(String name, SelectableChannel ch, Capability remoteCapability) {
            super(name, remoteCapability);

            this.ch = ch;
            this.rc = Closeables.input(ch);
            this.wc = Closeables.output(ch);
        }

        @Override
        ReadableByteChannel rr() {
            return (ReadableByteChannel) ch;
        }

        @Override
        WritableByteChannel ww() {
            return (WritableByteChannel) ch;
        }

        @Override
        boolean isWopen() {
            return wc != null;
        }

        @Override
        boolean isRopen() {
            return rc != null;
        }

        @Override
        @SelectorThreadOnly
        void closeR() throws IOException {
            if (rc != null) {
                rc.close();
                rc = null;
                rb.close(); // no more data will enter rb, so signal EOF
                maybeCancelKey();
            }
        }

        @Override
        @SelectorThreadOnly
        void closeW() throws IOException {
            if (wc != null) {
                wc.close();
                wc = null;
                wb.close(); // wb will not accept incoming data any more
                maybeCancelKey();
            }
        }

        @Override
        @SelectorThreadOnly
        public void reregister() throws IOException {
            int flag = (wantsToWrite() && isWopen() ? SelectionKey.OP_WRITE : 0)
                    + (wantsToRead() && isRopen() ? SelectionKey.OP_READ : 0);
            if (ch.isOpen()) {
                ch.configureBlocking(false);
                ch.register(selector, flag).attach(this);
            }
        }

        /**
         * If both directions are closed, cancel the whole key.
         */
        @SelectorThreadOnly
        private void maybeCancelKey() throws IOException {
            SelectionKey key = ch.keyFor(selector);
            if (rc == null && wc == null) {
                // both ends are closed
                cancelKey(key);
            } else {
                reregister();
            }
        }
    }

    /**
     * NioTransport that uses two {@link SelectableChannel}s to do read and write each.
     */
    class DualNioTransport extends NioTransport {
        private final SelectableChannel r, w;

        DualNioTransport(String name, SelectableChannel r, SelectableChannel w, Capability remoteCapability) {
            super(name, remoteCapability);

            assert r instanceof ReadableByteChannel && w instanceof WritableByteChannel;
            this.r = r;
            this.w = w;
        }

        @Override
        ReadableByteChannel rr() {
            return (ReadableByteChannel) r;
        }

        @Override
        WritableByteChannel ww() {
            return (WritableByteChannel) w;
        }

        @Override
        boolean isWopen() {
            return w.isOpen();
        }

        @Override
        boolean isRopen() {
            return r.isOpen();
        }

        @Override
        @SelectorThreadOnly
        void closeR() throws IOException {
            r.close();
            rb.close(); // no more data will enter rb, so signal EOF
            cancelKey(r);
        }

        @Override
        @SelectorThreadOnly
        void closeW() throws IOException {
            w.close();
            wb.close(); // wb will not accept incoming data any more
            cancelKey(w);
        }

        @Override
        @SelectorThreadOnly
        public void reregister() throws IOException {
            if (isRopen()) {
                r.configureBlocking(false);
                r.register(selector, wantsToRead() ? SelectionKey.OP_READ : 0).attach(this);
            }

            if (isWopen()) {
                w.configureBlocking(false);
                w.register(selector, wantsToWrite() ? SelectionKey.OP_WRITE : 0).attach(this);
            }
        }

        @SelectorThreadOnly
        private void cancelKey(SelectableChannel c) {
            assert c == r || c == w;
            cancelKey(c.keyFor(selector));
        }
    }

    /**
     *
     * @param commandProcessor
     *      Executor pool that delivers received command packets to {@link AbstractByteArrayCommandTransport.ByteArrayReceiver}.
     *      This pool will handle the deserialization (which may block due to classloading from the other side).
     */
    public NioChannelHub(ExecutorService commandProcessor) throws IOException {
        selector = Selector.open();
        this.commandProcessor = commandProcessor;
    }

    public void setFrameSize(int sz) {
        assert 0 < sz && sz <= Short.MAX_VALUE;
        this.transportFrameSize = sz;
    }

    /**
     * Returns a {@link ChannelBuilder} that will add a channel to this hub.
     *
     * <p>
     * If the way the channel is built doesn't support NIO, the resulting {@link Channel} will
     * use a separate thread to service its I/O.
     */
    public NioChannelBuilder newChannelBuilder(String name, ExecutorService es) {
        return new NioChannelBuilder(name, es) {
            // TODO: handle text mode

            @Override
            protected CommandTransport makeTransport(InputStream is, OutputStream os, Channel.Mode mode, Capability cap)
                    throws IOException {
                if (r == null) {
                    r = factory.create(is);
                }
                if (w == null) {
                    w = factory.create(os);
                }
                boolean disableNio = Boolean.getBoolean(NioChannelHub.class.getName() + ".disabled");
                if (r != null && w != null && mode == Channel.Mode.BINARY && cap.supportsChunking() && !disableNio) {
                    try {
                        // run() might be called asynchronously from another thread, so wait until that gets going
                        // if you see the execution hanging here forever, that means you forgot to call run()
                        // from another thread.
                        synchronized (startedLock) {
                            while (!started) {
                                startedLock.wait();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw (InterruptedIOException) new InterruptedIOException().initCause(e);
                    }

                    ensureValid();

                    NioTransport t;
                    if (r == w) {
                        t = new MonoNioTransport(getName(), r, cap);
                    } else {
                        t = new DualNioTransport(getName(), r, w, cap);
                    }
                    t.scheduleReregister();
                    return t;
                } else {
                    return super.makeTransport(is, os, mode, cap);
                }
            }
        };
    }

    // TODO: This logic should use Executor service
    private void scheduleSelectorTask(java.util.concurrent.Callable<Void> task) {
        selectorTasks.add(new CallableRemotingWrapper(task));
        selector.wakeup();
    }

    /**
     * Provides a wrapper for submitting {@link java.util.concurrent.Callable}s over Remoting execution queues.
     *
     * @deprecated It is just a hack, which schedules non-serializable tasks over the Remoting Task queue.
     *             There is no sane reason to reuse this wrapper class anywhere.
     */
    @Deprecated
    private static final class CallableRemotingWrapper implements Callable<Void, IOException> {
        private static final long serialVersionUID = -7331104479109353930L;
        final transient java.util.concurrent.Callable<Void> task;

        CallableRemotingWrapper(@NonNull java.util.concurrent.Callable<Void> task) {
            this.task = task;
        }

        @Override
        public Void call() throws IOException {
            if (task == null) {
                throw new IOException(
                        "The callable " + this + " has been serialized somehow, but it is actually not serializable");
            }
            try {
                return task.call();
            } catch (IOException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new IOException(ex);
            }
        }

        private Object readResolve() throws ObjectStreamException {
            throw new NotSerializableException("The class should not be serialized over Remoting");
        }

        private Object writeReplace() throws ObjectStreamException {
            throw new NotSerializableException("The class should not be serialized over Remoting");
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
            throw new SecurityException("The class should not be serialized over Remoting");
        }
    }

    /**
     * Shuts down the selector thread and aborts all
     */
    @Override
    public void close() throws IOException {
        selector.close();
    }

    /**
     * Attend to channels in the hub.
     *
     * This method returns when {@link #close()} is called and the selector is shut down.
     */
    @Override
    public void run() {
        synchronized (startedLock) {
            started = true;
            selectorThread = Thread.currentThread();
            startedLock.notifyAll();
        }
        final String oldName = selectorThread.getName();

        try {
            while (true) {
                try {
                    while (true) {
                        Callable<Void, IOException> t = selectorTasks.poll();
                        if (t == null) {
                            break;
                        }
                        try {
                            t.call();
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to process selectorTasks", e);
                            // but keep on at the next task
                        }
                    }

                    selectorThread.setName(
                            "NioChannelHub keys=" + selector.keys().size() + " gen=" + (gen++) + ": " + oldName);
                    selector.select();
                } catch (IOException e) {
                    whatKilledSelectorThread = e;
                    LOGGER.log(Level.WARNING, "Failed to select", e);
                    abortAll(e);
                    return;
                }

                Iterator<SelectionKey> itr = selector.selectedKeys().iterator();
                while (itr.hasNext()) {
                    SelectionKey key = itr.next();
                    itr.remove();
                    Object a = key.attachment();

                    if (a instanceof NioTransport) {
                        final NioTransport t = (NioTransport) a;

                        try {
                            if (key.isReadable()) {
                                if (t.rb.receive(t.rr()) == -1) {
                                    t.closeR();
                                }

                                final byte[] buf = new byte[ChunkHeader.SIZE];
                                int pos = 0;
                                int packetSize = 0;
                                while (true) {
                                    if (t.rb.peek(pos, buf) < ChunkHeader.SIZE) {
                                        break; // we don't have enough to parse header
                                    }
                                    int header = ChunkHeader.parse(buf);
                                    int chunk = ChunkHeader.length(header);
                                    pos += ChunkHeader.SIZE + chunk;
                                    packetSize += chunk;
                                    boolean last = ChunkHeader.isLast(header);
                                    if (last && pos <= t.rb.readable()) { // do we have the whole packet in our buffer?
                                        // read in the whole packet
                                        final byte[] packet = new byte[packetSize];
                                        int r_ptr = 0;
                                        do {
                                            int r = t.rb.readNonBlocking(buf);
                                            assert r == ChunkHeader.SIZE;
                                            header = ChunkHeader.parse(buf);
                                            chunk = ChunkHeader.length(header);
                                            last = ChunkHeader.isLast(header);
                                            t.rb.readNonBlocking(packet, r_ptr, chunk);
                                            packetSize -= chunk;
                                            r_ptr += chunk;
                                        } while (!last);
                                        assert packetSize == 0;
                                        if (packet.length > 0) {
                                            ExecutorServiceUtils.submitAsync(t.swimLane, () -> {
                                                final AbstractByteArrayCommandTransport.ByteArrayReceiver receiver =
                                                        t.receiver;
                                                if (receiver == null) {
                                                    throw new IllegalStateException(
                                                            "NIO transport layer has not been set up yet");
                                                }
                                                receiver.handle(packet);
                                            });
                                        }
                                        pos = 0;
                                    }
                                }

                                if (t.rb.writable() == 0 && t.rb.readable() > 0) {
                                    String msg = "Command buffer overflow. Read " + t.rb.readable()
                                            + " bytes but still too small for a single command";
                                    LOGGER.log(Level.WARNING, msg);
                                    // to avoid infinite hang, abort this connection
                                    t.abort(new IOException(msg));
                                }
                                if (t.rb.isClosed()) {
                                    // EOF. process this synchronously with respect to packets waiting for handling in
                                    // the queue
                                    ExecutorServiceUtils.submitAsync(t.swimLane, () -> {
                                        // if this EOF is unexpected, report an error.
                                        if (!t.getChannel().isInClosed()) {
                                            t.getChannel()
                                                    .terminate(new IOException(
                                                            "Unexpected EOF while receiving the data from the channel. "
                                                                    + "FIFO buffer has been already closed",
                                                            t.rb.getCloseCause()));
                                        }
                                    });
                                }
                            }
                            if (key.isValid() && key.isWritable()) {
                                t.wb.send(t.ww());
                                if (t.wb.readable() < 0) {
                                    // done with sending all the data
                                    t.closeW();
                                }
                            }
                            t.reregister();
                        } catch (IOException e) {
                            // It causes the channel failure, hence it is severe
                            LOGGER.log(
                                    Level.SEVERE,
                                    "Communication problem in " + t + ". NIO Transport will be aborted.",
                                    e);
                            t.abort(e);
                        } catch (ExecutorServiceUtils.ExecutionRejectedException e) {
                            // TODO: should we try to reschedule the task if the issue is not fatal?
                            // The swimlane has rejected the execution, e.g. due to the "shutting down" state.
                            LOGGER.log(
                                    Level.SEVERE,
                                    "The underlying executor service rejected the task in " + t
                                            + ". NIO Transport will be aborted.",
                                    e);
                            t.abort(e);
                        } catch (CancelledKeyException e) {
                            // see JENKINS-24050. I don't understand how this can happen, given that the selector
                            // thread is the only thread that cancels keys. So to better understand what's going on,
                            // report the problem.
                            LOGGER.log(
                                    Level.SEVERE,
                                    "Unexpected key cancellation for " + t + ". NIO Transport will be aborted.",
                                    e);
                            // to be on the safe side, abort the communication. if we don't do this, it's possible
                            // that the key never gets re-registered to the selector, and the traffic will hang
                            // on this channel.
                            t.abort(e);
                        }
                    } else {
                        onSelected(key);
                    }
                }
            }
        } catch (ClosedSelectorException e) {
            // end normally
            // TODO: what happens to all the registered ChannelPairs? don't we need to shut them down?
            whatKilledSelectorThread = e;
        } catch (RuntimeException | Error e) {
            whatKilledSelectorThread = e;
            LOGGER.log(Level.WARNING, "Unexpected shutdown of the selector thread", e);
            abortAll(e);
            throw e;
        } finally {
            selectorThread.setName(oldName);
            selectorThread = null;
            if (whatKilledSelectorThread == null) {
                whatKilledSelectorThread = new AssertionError("NioChannelHub shouldn't exit normally");
            }
        }
    }

    /**
     * Called when the unknown key registered to the selector is selected.
     */
    protected void onSelected(SelectionKey key) {}

    @SelectorThreadOnly
    private void abortAll(Throwable e) {
        Set<NioTransport> pairs = new HashSet<>();
        for (SelectionKey k : selector.keys()) {
            pairs.add((NioTransport) k.attachment());
        }
        for (NioTransport p : pairs) {
            p.abort(e);
        }
    }

    public Selector getSelector() {
        return selector;
    }

    /**
     * Verifies that the selector thread is running and this hub is active.
     *
     * Several bugs have been reported (such as JENKINS-24050) that causes the selector thread to die,
     * and several more bugs have been reported (such as JENKINS-24155 and JENKINS-24201) that are suspected
     * to be caused by the death of NIO selector thread.
     *
     * This check makes it easier to find this problem and report why the selector thread has died.
     */
    public void ensureValid() throws IOException {
        if (selectorThread == null) {
            throw new IOException("NIO selector thread is not running", whatKilledSelectorThread);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(NioChannelHub.class.getName());
}
