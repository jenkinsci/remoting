package org.jenkinsci.remoting.nio.fifo;

/**
 * Points to a specific position on buffer.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class BufferCursor implements Comparable<BufferCursor> {
    private BufferPage p;
    /**
     * [0,p.buf.size)
     */
    private int off;

    /*package*/ BufferCursor(BufferPage p, int off) {
        this.p = p;
        this.off = off;
    }

    /*packaage*/ BufferCursor(BufferCursor src) {
        this(src.p,src.off);
    }

    public abstract BufferWriter getWriter();

    public BufferReader newReader() {
        return new BufferReader(this);
    }

    public int compareTo(BufferCursor that) {
        int x = compare(this.p.n, that.p.n);
        if (x!=0)   return x;
        return compare(this.off,that.off);
    }

    private int compare(int a, int b) {
        if (a<b)    return -1;
        if (a>b)    return 1;
        return 0;
    }
}
