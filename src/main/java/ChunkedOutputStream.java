import java.io.IOException;
import java.io.OutputStream;

/**
 * Sends a series of byte[] in such a way that the NIO receiver can easily discover boundaries.
 *
 * <p>
 * This class implements that semantics by allowing the caller to make {@link #sendBreak()} to
 * signify a boundary between two byte[]s.
 *
 * <p>
 * The header is 2 bytes, in the network order. The first bit designates whether this chunk
 * is the last chunk (0 if this is the last chunk), and the remaining 15 bits designate the
 * length of the chunk as unsigned number.
 *
 * @author Kohsuke Kawaguchi
 */
public class ChunkedOutputStream extends OutputStream {
    private final byte[] buf;
    private int size;

    private final OutputStream base;

    public ChunkedOutputStream(int frameSize, OutputStream base) {
        assert 0<frameSize && frameSize<=Short.MAX_VALUE;

        this.buf = new byte[frameSize];
        size = 0;
        this.base = base;
    }

    private int frameSize() {
        return buf.length;
    }

    /**
     * How many more bytes can our buffer take?
     */
    private int capacity() {
        return buf.length-size;
    }

    @Override
    public void write(int b) throws IOException {
        buf[size++] = (byte)b;
        drain();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        while (len>0) {
            int s = Math.min(capacity(),len);
            System.arraycopy(b,off,buf,size,s);
            off+=s;
            len-=s;
            size+=s;
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
    public void close() throws IOException {
        sendFrame(false);
        base.close();
    }

    /**
     * If the buffer is filled up, send a frame.
     */
    private void drain() throws IOException {
        if (capacity()==0)
            sendFrame(true);
    }

    private void sendFrame(boolean hasMore) throws IOException {
        base.write((hasMore?0x80:0)|(size>>8));
        base.write(size&0xFF);
        base.write(buf,0,size);
        size = 0;
    }
}
