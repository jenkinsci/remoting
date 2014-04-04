package org.jenkinsci.remoting.nio;

import hudson.remoting.AbstractByteArrayCommandTransport;
import hudson.remoting.Callable;
import hudson.remoting.Capability;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Mode;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.ChunkHeader;
import hudson.remoting.CommandTransport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
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
import java.util.logging.Logger;

import static java.nio.channels.SelectionKey.*;
import static java.util.logging.Level.*;

/**
 * Switch board of multiple {@link Channel}s through NIO select.
 *
 * Through this hub, N threads can attend to M channels.
 *
 * @author Kohsuke Kawaguchi
 */
public class NioChannelHub implements Runnable, Closeable {
    private final Selector selector;
    /**
     * Maximum size of the chunk.
     */
    private final int transportFrameSize;
    private final SelectableFileChannelFactory factory = new SelectableFileChannelFactory();

    /**
     * Used to schedule work that can be only done synchronously with the {@link Selector#select()} call.
     */
    private final Queue<Callable<Void,IOException>> selectorTasks
            = new ConcurrentLinkedQueue<Callable<Void, IOException>>();

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
     * {@link MonoChannelPair} and {@link DualChannelPair} subtypes handle these differences.
     */
    abstract class ChannelPair extends AbstractByteArrayCommandTransport {
        private final Capability remoteCapability;

        /**
         * Where we pools bytes read from {@link #rr()} but not yet passed to {@link ByteArrayReceiver}.
         *
         * The receiver buffer has to be big enough to accommodate a single command in its entirety.
         * There's no size restriction in a command, so we'll just buffer as much as we can.
         */
        final FifoBuffer rb = new FifoBuffer(16*1024, Integer.MAX_VALUE);
        /**
         * Where we pools bytes to be send to {@link #ww()} but not yet done.
         */
        final FifoBuffer wb = new FifoBuffer(16*1024,256*1024);

        private ByteArrayReceiver receiver;

        ChannelPair(Capability remoteCapability) {
            this.remoteCapability = remoteCapability;
        }

        abstract ReadableByteChannel rr();

        abstract WritableByteChannel ww();

        /**
         * Based on the state of this {@link ChannelPair}, register NIO channels to the selector.
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
            // TODO: remove receiver!=null and have the selector thread just keep buffer them
            return receiver!=null && rb.writable()!=0;
        }

        /**
         * Returns true if we want to write to {@link #ww()}, namely
         * when we have some data in {@link #wb}.
         */
        boolean wantsToWrite() {
            return wb.readable()!=0;
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
            if (key!=null)
                key.cancel();
        }

        @SelectorThreadOnly
        public void abort(Throwable e) {
            try {
                closeR();
            } catch (IOException _) {
                // ignore
            }
            try {
                closeW();
            } catch (IOException _) {
                // ignore
            }
            receiver.terminate((IOException)new IOException("Failed to abort").initCause(e));
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
                    wb.write(bytes,pos,frame);
                    scheduleReregister();
                    pos+=frame;
                } while(hasMore);
            } catch (InterruptedException e) {
                throw (InterruptedIOException)new InterruptedIOException().initCause(e);
            }
        }

        @Override
        public void setup(ByteArrayReceiver receiver) {
            this.receiver = receiver;
            scheduleReregister();   // ready to read bytes now
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
            scheduleSelectorTask(new Callable<Void, IOException>() {
                public Void call() throws IOException {
                    closeR();
                    return null;
                }
            });
        }

        /**
         * Update the operations for which we are registered.
         */
        private void scheduleReregister() {
            scheduleSelectorTask(new Callable<Void, IOException>() {
                public Void call() throws IOException {
                    reregister();
                    return null;
                }
            });
        }
    }

    /**
     * ChannelPair that uses a single {@link SelectableChannel} to do both read and write.
     */
    class MonoChannelPair extends ChannelPair {
        private final SelectableChannel ch;
        /**
         * To close read and write end independently, we need to do half-close, which goes beyond
         * the contract of {@link SelectableChannel}. These objects represent the strategy to close them,
         * and when it's closed, set to null.
         */
        Closeable rc,wc;

        MonoChannelPair(SelectableChannel ch, Capability remoteCapability) {
            super(remoteCapability);

            this.ch = ch;
            this.rc = Closeables.input(ch);
            this.wc = Closeables.output(ch);
        }

        @Override
        ReadableByteChannel rr() {
            return (ReadableByteChannel)ch;
        }

        @Override
        WritableByteChannel ww() {
            return (WritableByteChannel)ch;
        }

        @Override
        boolean isWopen() {
            return wc!=null;
        }

        @Override
        boolean isRopen() {
            return rc!=null;
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
            if (wc!=null) {
                wc.close();
                wc = null;
                wb.close(); // wb will not accept incoming data any more
                maybeCancelKey();
            }
        }

        @Override
        @SelectorThreadOnly
        public void reregister() throws IOException {
            int flag = (wantsToWrite() && isWopen() ? OP_WRITE : 0) + (wantsToRead() && isRopen() ? OP_READ : 0);

            ch.configureBlocking(false);
            ch.register(selector, flag).attach(this);
        }

        @SelectorThreadOnly
        private void maybeCancelKey() throws IOException {
            SelectionKey key = ch.keyFor(selector);
            if (rc==null && wc==null) {
                // both ends are closed
                cancelKey(key);
            } else {
               reregister();
            }
        }

    }

    /**
     * ChannelPair that uses two {@link SelectableChannel}s to do read and write each.
     */
    class DualChannelPair extends ChannelPair {
        private final SelectableChannel r,w;

        DualChannelPair(SelectableChannel r, SelectableChannel w, Capability remoteCapability) {
            super(remoteCapability);

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
                r.register(selector, wantsToRead() ? OP_READ : 0).attach(this);
            }

            if (isWopen()) {
                w.configureBlocking(false);
                w.register(selector, wantsToWrite() ? OP_WRITE : 0).attach(this);
            }
        }

        @SelectorThreadOnly
        private void cancelKey(SelectableChannel c) {
            assert c==r || c==w;
            cancelKey(c.keyFor(selector));
        }
    }


    public NioChannelHub(int transportFrameSize) throws IOException {
        selector = Selector.open();
        assert 0<transportFrameSize && transportFrameSize<=Short.MAX_VALUE;
        this.transportFrameSize = transportFrameSize;
    }

    /**
     * Returns a {@link ChannelBuilder} that will add a channel to this hub.
     *
     * <p>
     * If the way the channel is built doesn't support NIO, the resulting {@link Channel} will
     * use a separate thread to service its I/O.
     */
    public NioChannelBuilder newChannelBuilder(String name, ExecutorService es) {
        return new NioChannelBuilder(name,es) {
            // TODO: handle text mode

            @Override
            protected CommandTransport makeTransport(InputStream is, OutputStream os, Mode mode, Capability cap) throws IOException {
                if (r==null)    r = factory.create(is);
                if (w==null)    w = factory.create(os);
                if (r!=null && w!=null && mode==Mode.BINARY && cap.supportsChunking()) {
                    ChannelPair cp;
                    if (r==w)       cp = new MonoChannelPair(r,cap);
                    else            cp = new DualChannelPair(r,w,cap);
                    cp.scheduleReregister();
                    return cp;
                }
                else
                    return super.makeTransport(is, os, mode, cap);
            }
        };
    }

    private void scheduleSelectorTask(Callable<Void, IOException> task) {
        selectorTasks.add(task);
        selector.wakeup();
    }

    /**
     * Shuts down the selector thread and aborts all
     */
    public void close() throws IOException {
        selector.close();
    }

    /**
     * Attend to channels in the hub.
     *
     * TODO: how does this method return?
     */
    public void run() {
        try {
            while (true) {
                try {
                    while (true) {
                        Callable<Void, IOException> t = selectorTasks.poll();
                        if (t==null)    break;
                        t.call();
                    }

                    selector.select();
                } catch (IOException e) {
                    LOGGER.log(WARNING, "Failed to select", e);
                    abortAll(e);
                    return;
                }

                Iterator<SelectionKey> itr = selector.selectedKeys().iterator();
                while (itr.hasNext()) {
                    SelectionKey key = itr.next();
                    itr.remove();
                    Object a = key.attachment();

                    if (a instanceof ChannelPair) {
                        ChannelPair cp = (ChannelPair) a;

                        try {
                            if (key.isReadable()) {
                                if (cp.rb.receive(cp.rr()) == -1) {
                                    cp.closeR();
                                }

                                final byte[] buf = new byte[2]; // space for reading the chunk header
                                int pos=0;
                                int packetSize=0;
                                while (true) {
                                    if (cp.rb.peek(pos,buf)<buf.length)
                                        break;  // we don't have enough to parse header
                                    int header = ChunkHeader.parse(buf);
                                    int chunk = ChunkHeader.length(header);
                                    pos+=buf.length+chunk;
                                    packetSize+=chunk;
                                    boolean last = ChunkHeader.isLast(header);
                                    if (last && pos<=cp.rb.readable()) {// do we have the whole packet in our buffer?
                                        // read in the whole packet
                                        byte[] packet = new byte[packetSize];
                                        int r_ptr = 0;
                                        while (packetSize>0) {
                                            int r = cp.rb.readNonBlocking(buf);
                                            assert r==buf.length;
                                            chunk = ChunkHeader.length(ChunkHeader.parse(buf));
                                            cp.rb.readNonBlocking(packet, r_ptr, chunk);
                                            packetSize-=chunk;
                                            r_ptr+=chunk;
                                        }
                                        assert packetSize==0;

                                        cp.receiver.handle(packet);
                                        pos=0;
                                    }
                                }

                                if (cp.rb.writable()==0) {
                                    String msg = "Command buffer overflow. Read " + cp.rb.readable() + " bytes but still too small for a single command";
                                    LOGGER.log(WARNING, msg);
                                    // to avoid infinite hang, abort this connection
                                    cp.abort(new IOException(msg));
                                }
                            }
                            if (key.isValid() && key.isWritable()) {
                                cp.wb.send(cp.ww());
                                if (cp.wb.readable()<0) {
                                    // done with sending all the data
                                    cp.closeW();
                                }
                            }
                            cp.reregister();
                        } catch (IOException e) {
                            LOGGER.log(WARNING, "Communication problem", e);
                            cp.abort(e);
                        }
                    } else {
                        onSelected(key);
                    }
                }
            }
        } catch (ClosedSelectorException e) {
            // end normally
            // TODO: what happens to all the registered ChannelPairs? don't we need to shut them down?
        } catch (RuntimeException e) {
            abortAll(e);
            LOGGER.log(WARNING, "Unexpected shutdown of the selector thread", e);
            throw e;
        } catch (Error e) {
            abortAll(e);
            LOGGER.log(WARNING, "Unexpected shutdown of the selector thread", e);
            throw e;
        }
    }

    /**
     * Called when the unknown key registered to the selector is selected.
     */
    protected void onSelected(SelectionKey key) {

    }

    @SelectorThreadOnly
    private void abortAll(Throwable e) {
        Set<ChannelPair> pairs = new HashSet<ChannelPair>();
        for (SelectionKey k : selector.keys())
            pairs.add((ChannelPair)k.attachment());
        for (ChannelPair p : pairs)
            p.abort(e);
    }

    public Selector getSelector() {
        return selector;
    }

    private static final Logger LOGGER = Logger.getLogger(NioChannelHub.class.getName());
}
