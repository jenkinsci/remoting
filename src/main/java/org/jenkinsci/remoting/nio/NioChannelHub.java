package org.jenkinsci.remoting.nio;

import hudson.remoting.AbstractByteArrayCommandTransport;
import hudson.remoting.Capability;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Mode;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.CommandTransport;
import org.apache.commons.io.IOUtils;

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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
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

    class ChannelPair extends AbstractByteArrayCommandTransport {
        final SelectableChannel r,w;
        final ReadableByteChannel rr;
        final WritableByteChannel ww;
        private final Capability remoteCapability;

        /**
         * Where we pools bytes read from {@link #r} but not yet processed.
         */
        final FifoBuffer rb = new FifoBuffer(16*1024,256*1024);
        /**
         * Where we pools bytes to be send to {@link #w} but not yet done.
         */
        final FifoBuffer wb = new FifoBuffer(16*1024,256*1024);

        private ByteArrayReceiver receiver;

        ChannelPair(SelectableChannel r, SelectableChannel w, Capability remoteCapability) {
            this.r = r;
            this.w = w;
            this.rr = (ReadableByteChannel) r;
            this.ww = (WritableByteChannel) w;
            this.remoteCapability = remoteCapability;
        }

        public void register() throws IOException {
            r.register(selector, SelectionKey.OP_READ).attach(this);
            w.register(selector, SelectionKey.OP_WRITE).attach(this);
        }

        public void abort(Exception e) {
            cancelKey(r);
            cancelKey(w);
            rb.close();
            receiver.terminate((IOException)new IOException("Failed to abort").initCause(e));
        }

        @Override
        public void writeBlock(Channel channel, byte[] bytes) throws IOException {
            try {
                byte[] header = new byte[2];

                int pos = 0;
                boolean last;
                do {
                    int frame = Math.min(transportFrameSize,bytes.length-pos);
                    last = frame!=transportFrameSize;
                    header[0] = (byte)((last?0x80:0)|(frame>>8));
                    header[1] = (byte)(frame);
                    wb.write(header,0,header.length);
                    wb.write(bytes,pos,frame);
                    pos+=frame;
                } while(!last);
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
        }

        @Override
        public void setup(ByteArrayReceiver receiver) {
            this.receiver = receiver;
        }

        @Override
        public Capability getRemoteCapability() throws IOException {
            return remoteCapability;
        }

        @Override
        public void closeWrite() throws IOException {
            wb.close();
        }

        @Override
        public void closeRead() throws IOException {
            cancelKey(r);
        }
    }

    public NioChannelHub(int transportFrameSize) throws IOException {
        selector = Selector.open();
        assert 0<transportFrameSize && transportFrameSize<=Short.MAX_VALUE;
        this.transportFrameSize = transportFrameSize;
    }

    public ChannelBuilder newChannelBuilder(String name, ExecutorService es) {
        return new ChannelBuilder(name,es) {
            // TODO: handle text mode

            @Override
            protected CommandTransport makeTransport(InputStream is, OutputStream os, Mode mode, Capability cap) throws IOException {
                SocketChannel r = factory.create(is);
                SocketChannel w = factory.create(os);
                if (r!=null && r!=null && mode==Mode.BINARY && true/*TODO: check if framing is suported*/) {
                    ChannelPair cp = new ChannelPair(r, w, cap);
                    cp.register();
                    return cp;
                }
                else
                    return super.makeTransport(is, os, mode, cap);
            }
        };
    }

    public void run() {
        while (true) {
            try {
                selector.select();
            } catch (ClosedSelectorException e) {
                abortAll(e);
                return;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to select", e);
                abortAll(e);
                return;
            }

            for (SelectionKey key : selector.selectedKeys()) {
                Object a = key.attachment();
                if (a instanceof ChannelPair) {
                    ChannelPair p = (ChannelPair) a;

                    try {
                        if (key.isReadable()) {
                            if (p.rb.receive(p.rr) == -1) {
                                cancelKey(key);
                                p.rb.close();
                            }

                            final byte[] buf = new byte[2]; // space for reading the chunk header
                            int pos=0;
                            int packetSize=0;
                            while (true) {
                                if (p.rb.peek(pos,buf)<buf.length)
                                    break;  // we don't have enough
                                int header = ChunkHeader.parse(buf);
                                int chunk = ChunkHeader.length(header);
                                pos+=buf.length+chunk;
                                packetSize+=chunk;
                                boolean last = ChunkHeader.isLast(header);
                                if (last && pos<=p.rb.readable()) {// do we have the whole packet in our buffer?
                                    // read in the whole packet
                                    byte[] packet = new byte[packetSize];
                                    int r_ptr = 0;
                                    while (packetSize>0) {
                                        int r = p.rb.readNonBlocking(buf);
                                        assert r==buf.length;
                                        chunk = ChunkHeader.length(ChunkHeader.parse(buf));
                                        p.rb.readNonBlocking(packet, r_ptr, chunk);
                                        packetSize-=chunk;
                                        r_ptr+=chunk;
                                    }
                                    assert packetSize==0;

                                    p.receiver.handle(packet);
                                }
                            }
                        }
                        if (key.isWritable()) {
                            if (p.wb.send(p.ww) == -1) {
                                // done with sending all the data
                                cancelKey(key);
                            }
                        }
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Communication problem", e);
                        p.abort(e);
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

    private void cancelKey(SelectableChannel ch) {
        cancelKey(ch.keyFor(selector));
    }

    private void cancelKey(SelectionKey key) {
        if (key!=null) {
            key.cancel();
            IOUtils.closeQuietly(key.channel());
        }
    }

    public Selector getSelector() {
        return selector;
    }

    private static final Logger LOGGER = Logger.getLogger(NioChannelHub.class.getName());
}
