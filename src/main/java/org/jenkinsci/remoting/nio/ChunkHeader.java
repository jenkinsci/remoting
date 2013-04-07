package org.jenkinsci.remoting.nio;

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
class ChunkHeader {
    static int parse(byte[] buf) {
        return parse(buf, 0);
    }

    static int parse(byte[] buf, int pos) {
        return parse(buf[pos], buf[pos + 1]);
    }

    static int parse(int b1, int b2) {
        return ((b1&0xFF)<<8) | (b2&0xFF);
    }

    static boolean isLast(int header) {
        return (header&0x8000)==0;
    }

    static int length(int header) {
        return header&0x7FFF;
    }
}
