package org.jenkinsci.remoting.nio;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * FIFO buffer for a reader thread and a writer thread to collaborate.
 *
 * Unlike a ring buffer, which uses a fixed memory regardless of the number of bytes currently in the buffer,
 * this implementation uses a single linked list to reduce the memory footprint when the reader
 * closely follows the writer, regardless of the capacity limit set in the constructor.
 *
 * In trilead, the writer puts the data we receive from the network, and the user code acts as a reader.
 * A user code normally drains the buffer more quickly than what the network delivers, so this implementation
 * saves memory while simultaneously allowing us to advertise a bigger window size for a large latency network.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.38
 */
public class FifoBuffer implements Closeable {
    /**
     * Unit of buffer, singly linked and lazy created as needed.
     */
    static final class Page {
        final byte[] buf;
        Page next;

        Page(int sz) {
            this.buf = new byte[sz];
        }
    }

    /**
     * Points to a specific byte in a {@link Page}.
     */
    class Pointer {
        Page p;
        /**
         * [0,p.buf.size)
         */
        int off;

        Pointer(Page p, int off) {
            this.p = p;
            this.off = off;
        }

        Pointer copy() {
            return new Pointer(p,off);
        }

        void forward(int offset) {
            while (offset>0) {
                int ch = Math.min(offset,chunk());
                assert 0<ch && ch<=offset;
                offset -= ch;
                off += ch;
            }
        }

        /**
         * Figure out the number of bytes that can be read/written in one array copy.
         */
        private int chunk() {
            int sz = pageSize-off;
            assert sz>=0;

            if (sz>0)   return sz;

            Page q = p.next;
            if (q==null)
                q = p.next = newPage();
            p = q;
            off = 0;
            return pageSize;
        }

        public void write(ByteBuffer buf, int len) {
            while (len>0) {
                int chunk = Math.min(len,chunk());
                buf.get(p.buf, off, chunk);

                off+=chunk;
                len-=chunk;
            }
        }

        public void write(byte[] buf, int start, int len) {
            while (len>0) {
                int chunk = Math.min(len,chunk());
                System.arraycopy(buf,start,p.buf,off,chunk);

                off+=chunk;
                len-=chunk;
                start+=chunk;
            }
        }

        public void read(byte[] buf, int start, int len) {
            while (len>0) {
                int chunk = Math.min(len,chunk());
                assert off+chunk <= p.buf.length;
                assert start+chunk <= buf.length;
                assert off>=0;
                assert start>=0;
                assert chunk>=0;
                System.arraycopy(p.buf,off,buf,start,chunk);

                off+=chunk;
                len-=chunk;
                start+=chunk;
            }
        }

        /**
         * Returns the current page as a {@link ByteBuffer}.
         */
        private ByteBuffer asBuffer(int max) {
            int ch = chunk();   // this needs to be done first
            return ByteBuffer.wrap(p.buf,off,Math.min(ch,max));
        }

        public int send(WritableByteChannel ch, int max) throws IOException {
            int n = ch.write(asBuffer(max));
            off+=n;
            return n;
        }

        public int receive(ReadableByteChannel ch, int max) throws IOException {
            int n = ch.read(asBuffer(max));
            if (n>=0)   off+=n;
            return n;
        }
    }

    private final Object lock;

    /**
     * Number of bytes currently in this ring buffer
     */
    private int sz;
    /**
     * Cap to the # of bytes that we can hold.
     */
    private int limit;
    private final int pageSize;

    /**
     * The position at which the next read/write will happen.
     */
    private Pointer r,w;

    /**
     * Set to true when the writer closes the write end.
     */
    private boolean closed;

    public FifoBuffer(int pageSize, int limit) {
        this(null,pageSize,limit);
    }

    public FifoBuffer(int limit) {
        this(Math.max(limit/256,1024),limit);
    }

    public FifoBuffer(Object lock, int pageSize, int limit) {
        this.lock = lock==null ? this : lock;
        this.limit = limit;
        this.pageSize = pageSize;

        Page p = newPage();
        r = new Pointer(p,0);
        w = new Pointer(p,0);
    }

    /**
     * Set limit to the number of maximum bytes this buffer can hold.
     *
     * Write methods will block if the size reaches the limit.
     */
    public void setLimit(int newLimit) {
        synchronized (lock) {
            limit = newLimit;
        }
    }

    private Page newPage() {
        return new Page(pageSize);
    }

    /**
     * Number of bytes available in this buffer that are readable.
     *
     * @return
     *      -1 if the buffer is closed and there's no more data to read.
     */
    public int readable() {
        synchronized (lock) {
            if (sz>0)   return sz;
            if (closed) return -1;
            return 0;
        }
    }

    /**
     * Number of bytes writable
     */
    public int writable() {
        return Math.max(0,limit-readable());
    }

    /**
     * Returns true if the write end of the buffer is already closed, and that
     * no more new data will arrive.
     *
     * Note that there still might be a data left in the buffer to be read.
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Read from this buffer write as much as possible to the channel until
     * the channel gets filled up.
     *
     * @return
     *      number of bytes that were written. -1 if this buffer is EOF-ed and there will never be
     *      any more data to write.
     */
    public int send(WritableByteChannel ch) throws IOException {
        int read = 0;   // total # of bytes read

        while (true) {
            synchronized (lock) {
                int chunk = readable();
                if (chunk<=0) {
                    // there's nothing we can immediately read

                    if (read>0)     return read;    // we've already read some

                    if (closed) {
                        releaseRing();
                        return -1;  // no more data
                    }
                    return 0;   // no data to read
                }
                try {
                    int sent = r.send(ch, chunk);  // bytes actually written

                    read += sent;
                    sz -= sent;

                    lock.notifyAll();

                    if (sent == 0)    // channel filled up
                        return read;
                } catch (ClosedChannelException e) {
                    return -1; // propagate EOF
                }
            }
        }
    }


    /**
     * Non-blocking write.
     *
     * @return
     *      Number of writes written, possibly 0.
     */
    public int writeNonBlock(ByteBuffer buf) {
        synchronized (lock) {
            int chunk = Math.min(buf.remaining(),writable());
            if (chunk==0)   return 0;

            w.write(buf,chunk);

            sz += chunk;

            lock.notifyAll();

            return chunk;
        }
    }

    /**
     * Read bytes from a channel and stores it into this buffer.
     *
     * @return
     *      number of bytes read, or -1 if the given channel has reached EOF and no further read is possible.
     */
    public int receive(ReadableByteChannel ch) throws IOException {
        if (closed)
            throw new IOException("already closed");

        int written = 0;
        while (true) {
            synchronized (lock) {
                int chunk = writable();
                if (chunk==0)
                    return written; // no more space to write
                try {
                    int received = w.receive(ch, chunk);
                    if (received==0)
                        return written; // channel is fully drained
                    if (received==-1) {
                        if (written==0)     return -1; // propagate EOF
                        return written;
                    }

                    sz += received;
                    written += received;
                } catch (ClosedChannelException e) {
                    if (written == 0) return -1; // propagate EOF
                    return written;
                }

                lock.notifyAll();

            }
        }
    }

    public void write(byte[] buf) throws InterruptedException, IOException {
        write(buf,0,buf.length);
    }

    public void write(byte[] buf, int start, int len) throws InterruptedException, IOException {
        if (closed)
            throw new IOException("already closed");

        while (len>0) {
            int chunk;

            synchronized (lock) {
                while ((chunk = Math.min(len,writable()))==0) {
                    if (closed)
                        throw new IOException("closed during write operation");

                    lock.wait(100);
                }

                w.write(buf, start, chunk);

                start += chunk;
                len -= chunk;
                sz += chunk;

                lock.notifyAll();
            }
        }
    }

    /**
     * Indicates that there will be no more write.
     *
     * Once the remaining bytes are drained by the reader, the read method will start
     * returning EOF signals.
     */
    public void close() {
        synchronized (lock) {
            if (!closed) {
                closed = true;
                releaseRing();
                lock.notifyAll();
            }
        }
    }

    /**
     * If the ring is no longer needed, release the buffer.
     */
    private void releaseRing() {
        if (readable()<0)
            r = w = null;
    }

//    /**
//     * Returns a {@link ByteBuffer} for peeking into
//     */
//    public ByteBuffer peek(int max) {
//        synchronized (lock) {
//            if (closed) {
//                releaseRing();
//                return null;
//            }
//            return r.asBuffer(Math.min(max, readable()));
//        }
//    }

    /**
     * Peek the specified number of bytes ({@code len}) at the specified offset in this buffer ({@code offset})
     * and places it into the specified position ({@code start}) of the array ({@code data})
     *
     * @return
     *      number of bytes actually peeked. Can be 0 if the offset goes beyond the current readable size in this buffer.
     *      Never negative.
     */
    public int peek(int offset, byte[] data, int start, int len) {
        synchronized (lock) {
            len = Math.min(len, readable()-offset); // can't read beyond the end of the readable buffer
            if (len<=0) return 0;

            Pointer v = this.r.copy();
            v.forward(offset);
            v.read(data,start,len);
            return len;
        }
    }

    public int peek(int offset, byte[] data) {
        return peek(offset,data,0,data.length);
    }

    public int read(byte[] buf) throws InterruptedException {
        return read(buf,0,buf.length);
    }

    /**
     *
     * @see InputStream#read(byte[],int,int)
     */
    public int read(byte[] buf, int start, int len) throws InterruptedException {
        if (len==0)     return 0;   // the only case where we can legally return 0
        synchronized (lock) {
            while (true) {
                int r = readNonBlocking(buf,start,len);
                if (r!=0)   return r;
                lock.wait();    // wait until the writer gives us something
            }
        }
    }

    public int readNonBlocking(byte[] buf) {
        return readNonBlocking(buf, 0, buf.length);
    }

    /**
     *
     * @see InputStream#read(byte[],int,int)
     */
    public int readNonBlocking(byte[] buf, int start, int len) {
        if (len==0)     return 0;

        int read = 0;   // total # of bytes read

        while (true) {
            int chunk;

            synchronized (lock) {
                while (true) {
                    chunk = Math.min(len,readable());
                    if (chunk>0)    break;

                    // there's nothing we can immediately read

                    if (read>0)     return read;    // we've already read some

                    if (closed) {
                        releaseRing();
                        return -1;  // no more data
                    }

                    return 0; // nothing to read
                }

                r.read(buf,start,chunk);

                start += chunk;
                len -= chunk;
                read += chunk;
                sz -= chunk;

                lock.notifyAll();
            }
        }
    }

    /**
     * Wraps writer end of it to {@link OutputStream}.
     */
    public OutputStream getOutputStream() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                try {
                    byte[] buf = new byte[]{(byte)b};
                    FifoBuffer.this.write(buf);
                } catch (InterruptedException e) {
                    throw (InterruptedIOException)new InterruptedIOException().initCause(e);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                try {
                    FifoBuffer.this.write(b,off,len);
                } catch (InterruptedException e) {
                    throw (InterruptedIOException)new InterruptedIOException().initCause(e);
                }
            }

            @Override
            public void close() throws IOException {
                FifoBuffer.this.close();
            }
        };
    }

    /**
     * Wraps the reader end of it to {@link InputStream}
     */
    public InputStream getInputStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    byte[] b = new byte[1];
                    int n = FifoBuffer.this.read(b);
                    if (n<0)    return -1;
                    if (n==0)   throw new AssertionError();
                    return ((int)b[0])&0xFF;
                } catch (InterruptedException e) {
                    throw (InterruptedIOException)new InterruptedIOException().initCause(e);
                }
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                try {
                    return FifoBuffer.this.read(b, off, len);
                } catch (InterruptedException e) {
                    throw (InterruptedIOException)new InterruptedIOException().initCause(e);
                }
            }
        };
    }
}
