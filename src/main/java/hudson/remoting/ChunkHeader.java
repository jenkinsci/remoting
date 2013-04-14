package hudson.remoting;

/**
 * Parsing of the chunk header.
 *
 * <p>
 * The header is 2 bytes, in the network order. The first bit designates whether this chunk
 * is the last chunk (0 if this is the last chunk), and the remaining 15 bits designate the
 * length of the chunk as unsigned number.
 *
 * @author Kohsuke Kawaguchi
 */
public class ChunkHeader {
    public static int parse(byte[] buf) {
        return parse(buf, 0);
    }

    public static int parse(byte[] buf, int pos) {
        return parse(buf[pos], buf[pos + 1]);
    }

    public static int parse(int b1, int b2) {
        return ((b1&0xFF)<<8) | (b2&0xFF);
    }

    public static boolean isLast(int header) {
        return (header&0x8000)==0;
    }

    public static int length(int header) {
        return header&0x7FFF;
    }

    public static byte[] pack(int length, boolean hasMore) {
        byte[] header = new byte[2];
        header[0] = (byte)((hasMore?0x80:0)|(length>>8));
        header[1] = (byte)(length);
        return header;
    }
}