package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Opposite of {@link ChunkedOutputStream}.
 *
 * <p>
 * {@link #onBreak()} method signifies the chunk boundary.
 *
 * @author Kohsuke Kawaguchi
 */
class ChunkedInputStream extends InputStream {
    private final InputStream base;

    /**
     * Number of bytes remaining in the current chunk.
     */
    private int remaining;

    /**
     * True if we are reading the last block of a chunk.
     */
    private boolean isLast;

    public ChunkedInputStream(InputStream base) {
        this.base = base;
    }

    @Override
    public int read() throws IOException {
        if (nextPayload()) {
            return -1;
        }
        int x = base.read();
        remaining--;
        return x;
    }

    @Override
    public int read(@NonNull byte[] b, int off, int len) throws IOException {
        if (nextPayload()) {
            return -1;
        }

        len = Math.min(remaining, len);

        int x = base.read(b, off, len);
        if (x < 0) {
            return x;
        }

        remaining -= x;
        return x;
    }

    /**
     * If we are supposed to read the length of the next chunk, do so.
     */
    private boolean nextPayload() throws IOException {
        while (remaining == 0) {
            if (readHeader()) {
                return true;
            }
        }
        return false;
    }

    /**
     *
     * @return
     *      true if the underlying stream hits EOF
     */
    private boolean readHeader() throws IOException {
        if (remaining > 0) {
            return false;
        }

        int b1 = base.read();
        int b2 = base.read();
        if (b1 < 0 || b2 < 0) {
            return true; // EOF
        }

        int header = ChunkHeader.parse(b1, b2);
        if (isLast = ChunkHeader.isLast(header)) {
            onBreak();
        }
        remaining = ChunkHeader.length(header);
        return false;
    }

    /**
     * Signifies the chunk boundary.
     */
    protected void onBreak() {}

    /**
     * Reads bytes until we hit the chunk boundary. Bytes read will be written to the sink.
     */
    public void readUntilBreak(OutputStream sink) throws IOException {
        byte[] buf = new byte[4096];
        while (true) {
            if (remaining > 0) {
                // more bytes to read in the current chunk
                int read = read(buf, 0, Math.min(remaining, buf.length));
                if (read == -1) {
                    throw new IOException("Unexpected EOF");
                }
                sink.write(buf, 0, read);
            } else {
                // move on to the next chunk
                if (readHeader()) {
                    return; // stream has EOFed. No more bytes to read.
                }
            }
            if (isLast && remaining == 0) {
                return; // we've read the all payload of the last chunk
            }
        }
    }

    @Override
    public void close() throws IOException {
        base.close();
    }
}
