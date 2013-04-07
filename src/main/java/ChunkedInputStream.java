import java.io.IOException;
import java.io.InputStream;

/**
 * Opposite of {@link ChunkedOutputStream}.
 *
 * <p>
 * {@link #onBreak()} method signifies the chunk boundary.
 *
 * @author Kohsuke Kawaguchi
 */
public class ChunkedInputStream extends InputStream {
    private final InputStream base;

    /**
     * Number of bytes remaining in the current chunk.
     */
    private int remaining;

    public ChunkedInputStream(InputStream base) {
        this.base = base;
    }

    @Override
    public int read() throws IOException {
        if (readLength())   return -1;
        int x = base.read();
        remaining--;
        return x;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (readLength())   return -1;

        len = Math.min(remaining,len);

        int x = base.read(b,off,len);
        if (x<0)    return x;

        remaining -= x;
        return x;
    }

    /**
     * If we are supposed to read the length of the next chunk, do so.
     */
    private boolean readLength() throws IOException {
        while (remaining==0) {
            int b1 = base.read();
            int b2 = base.read();
            if (b1<0 || b2<0)   return true; // EOF

            if ((b1&0x80)==0)
                onBreak();
            remaining = ((b1<<8)&0x7F)+b2;
        }
        return false;
    }

    /**
     * Signifies the chunk boundary.
     */
    protected void onBreak() {
    }
}
