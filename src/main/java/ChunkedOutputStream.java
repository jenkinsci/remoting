import java.io.IOException;
import java.io.OutputStream;

/**
 * @author Kohsuke Kawaguchi
 */
public class ChunkedOutputStream extends OutputStream {
    private final byte[] buf;
    private int size;

    private final OutputStream base;

    public ChunkedOutputStream(int frameSize, OutputStream base) {
        assert 0<frameSize && frameSize<=0x1000;

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
        sendFrame();
        base.flush();
    }

    @Override
    public void close() throws IOException {
        sendFrame();
        base.close();
    }

    /**
     * If the buffer is filled up, send a frame.
     */
    private void drain() throws IOException {
        if (capacity()==0)
            sendFrame();
    }

    private void sendFrame() throws IOException {
        base.write(size>>8);
        base.write(size&0xFF);
        base.write(buf,0,size);
        size = 0;
    }
}
