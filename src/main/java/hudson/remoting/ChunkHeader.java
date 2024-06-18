package hudson.remoting;

import java.nio.ByteBuffer;
import org.jenkinsci.remoting.util.ByteBufferQueue;

/**
 * Parsing of the chunk header.
 *
 * <p>
 * The header is {@link #SIZE} bytes, in the network order. The first bit designates whether this chunk
 * is the last chunk (0 if this is the last chunk), and the remaining 15 bits designate the
 * length of the chunk as unsigned number.
 *
 * @author Kohsuke Kawaguchi
 */
public class ChunkHeader {

    public static final int SIZE = 2;

    public static int read(ByteBuffer buf) {
        return parse(buf.get(), buf.get());
    }

    public static int peek(ByteBuffer buf) {
        return peek(buf, 0);
    }

    public static int peek(ByteBuffer buf, int pos) {
        return parse(buf.get(pos), buf.get(pos + 1));
    }

    public static int parse(byte[] buf) {
        return parse(buf, 0);
    }

    public static int parse(byte[] buf, int pos) {
        return parse(buf[pos], buf[pos + 1]);
    }

    public static int parse(int b1, int b2) {
        return ((b1 & 0xFF) << 8) | (b2 & 0xFF);
    }

    public static boolean isLast(int header) {
        return (header & 0x8000) == 0;
    }

    public static int length(int header) {
        return header & 0x7FFF;
    }

    public static void write(ByteBuffer buf, int length, boolean hasMore) {
        buf.put((byte) ((hasMore ? 0x80 : 0) | (length >> 8)));
        buf.put((byte) length);
    }

    public static void write(ByteBufferQueue buf, int length, boolean hasMore) {
        buf.put((byte) ((hasMore ? 0x80 : 0) | (length >> 8)));
        buf.put((byte) length);
    }

    public static byte[] pack(int length, boolean hasMore) {
        byte[] header = new byte[SIZE];
        header[0] = (byte) ((hasMore ? 0x80 : 0) | (length >> 8));
        header[1] = (byte) (length);
        return header;
    }
}
