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
import java.nio.channels.WritableByteChannel;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.channels.SelectionKey.*;

/**
 * Switch board of multiple {@link Channel}s through NIO select.
 *
 * Through this hub, N threads can attend to M channels.
 *
 * @author Kohsuke Kawaguchi
 */
public class NioChannelHub implements Runnable {
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
     * A pair of NIO channels used as the transport of a {@link Channel}.
     *
     * <p>
     * The read end of it has to be a {@link Channel} that is both selectable and readable.
     * There's no single type that captures this, so we rely on {@link #rr()} and {@link #ww()} to convey this idea.
     *
     * <p>
     * Sometimes the read end and the write end are the same object, as in the case of socket,
     * yet in remoting we have to be able to close read and write ends separately. So we take
     * separate {@link Closeable} objects that abstracts away how we hide each end, which are
     * {@link #rc} and {@link #wc}. When it is closed, the field is set to null to indicate
     *  the channel is closed.
     */
    class ChannelPair extends AbstractByteArrayCommandTransport {
        private final SelectableChannel r,w;
        Closeable rc,wc;
        private final Capability remoteCapability;

        /**
         * Where we pools bytes read from {@link #r} but not yet passed to {@link ByteArrayReceiver}.
         */
        final FifoBuffer rb = new FifoBuffer(16*1024,256*1024);
        /**
         * Where we pools bytes to be send to {@link #w} but not yet done.
         */
        final FifoBuffer wb = new FifoBuffer(16*1024,256*1024);

        private ByteArrayReceiver receiver;

        ChannelPair(SelectableChannel r, SelectableChannel w, Capability remoteCapability) {
            assert r instanceof ReadableByteChannel && w instanceof WritableByteChannel && rc!=null && wc!=null;
            this.r = r;
            this.w = w;
            this.rc = Closeables.input(r);
            this.wc = Closeables.input(w);
            this.remoteCapability = remoteCapability;
        }

        ReadableByteChannel rr() {
            return (ReadableByteChannel) r;
        }

        WritableByteChannel ww() {
            return (WritableByteChannel) w;
        }

        public void reregister() throws IOException {
            int writeFlag = wb.readable()!=0 ? OP_WRITE : 0; // do we want to write? if -1, we want to trigger closeW(), so we return OP_WRITE
            int readFlag = receiver!=null ? OP_READ : 0; // once we have the setup method called, we are ready
            boolean registered = false;

            if (isRopen()) {
                int rflag = (r==w) ? readFlag|writeFlag : readFlag;
                r.configureBlocking(false);
                r.register(selector, rflag).attach(this);
                registered = true;
            }

            if (isWopen() && !registered) {
                w.configureBlocking(false);
                w.register(selector, writeFlag).attach(this);
            }
        }

        private boolean isWopen() {
            return wc!=null;
        }

        private boolean isRopen() {
            return rc!=null;
        }

        void closeR() throws IOException {
            if (rc!=null) {
                rc.close();
                rc = null;
                rb.close(); // no more data will enter rb, so signal EOF
                cancelKey(r);
            }
        }

        void closeW() throws IOException {
            if (wc!=null) {
                wc.close();
                wc = null;
                cancelKey(w);
            }
        }

        private void cancelKey(SelectableChannel c) {
            assert c==r || c==w;
            if (r!=w) {
                cancelKey(c.keyFor(selector));
            } else {
                // if r==w we have to wait for both sides to close before we cancel key
                if (rc==null && wc==null)
                    cancelKey(c.keyFor(selector));
            }
        }

        private void cancelKey(SelectionKey key) {
            if (key!=null)
                key.cancel();
        }

        public void abort(Exception e) {
            cancelKey(r);
            cancelKey(w);
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
            closeR();
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
                    final ChannelPair cp = new ChannelPair(r, w, cap);
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
     * Attend to channels in the hub.
     *
     * TODO: how does this method return?
     */
    public void run() {
        while (true) {
            try {
                while (true) {
                    Callable<Void, IOException> t = selectorTasks.poll();
                    if (t==null)    break;
                    t.call();
                }

                selector.select();
            } catch (ClosedSelectorException e) {
                abortAll(e);
                return;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to select", e);
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
                                }
                            }
                        }
                        if (key.isWritable()) {
                            cp.wb.send(cp.ww());
                            if (cp.wb.readable()<0) {
                                // done with sending all the data
                                cp.closeW();
                            }
                        }
                        cp.reregister();
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Communication problem", e);
                        cp.abort(e);
                    }
                } else {
                    onSelected(key);
                }
            }
        }
    }

    /**
     * Called when the unknown key registered to the selector is selected.
     */
    protected void onSelected(SelectionKey key) {

    }

    private void abortAll(Exception e) {
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
