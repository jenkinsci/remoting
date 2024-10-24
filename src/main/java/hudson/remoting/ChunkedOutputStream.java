package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Sends a series of byte[] in such a way that each byte array is framed and boundaries are easily distinguishable.
 * Think of this as a stream with a boundary marker in between.
 *
 * <p>
 * This framing is used to allow NIO to buffer a whole {@link Command} until it completes.
 *
 * <p>
 * This class implements that semantics by allowing the caller to make {@link #sendBreak()} to
 * signify a boundary between two byte[]s.
 *
 * @author Kohsuke Kawaguchi
 */
class ChunkedOutputStream extends OutputStream {
    private final byte[] buf;
    private int size;

    private final OutputStream base;

    public ChunkedOutputStream(int frameSize, OutputStream base) {
        if (frameSize < 0 || frameSize > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Illegal frame size: " + frameSize);
        }

        this.buf = new byte[frameSize];
        size = 0;
        this.base = base;
    }

    /**
     * How many more bytes can our buffer take?
     */
    private int capacity() {
        return buf.length - size;
    }

    @Override
    public void write(int b) throws IOException {
        buf[size++] = (byte) b;
        drain();
    }

    @Override
    public void write(@NonNull byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int s = Math.min(capacity(), len);
            System.arraycopy(b, off, buf, size, s);
            off += s;
            len -= s;
            size += s;
            drain();
        }
    }

    /**
     * Sends a boundary of a packet.
     */
    public void sendBreak() throws IOException {
        sendFrame(false);
        base.flush();
    }

    @Override
    public void flush() throws IOException {
        if (size > 0) {
            sendFrame(true);
            base.flush();
        }
    }

    @Override
    public void close() throws IOException {
        sendFrame(false);
        base.close();
    }

    /**
     * If the buffer is filled up, send a frame.
     */
    private void drain() throws IOException {
        if (capacity() == 0) {
            sendFrame(true);
        }
    }

    private void sendFrame(boolean hasMore) throws IOException {
        base.write(ChunkHeader.pack(size, hasMore));
        base.write(buf, 0, size);
        size = 0;
    }
}
