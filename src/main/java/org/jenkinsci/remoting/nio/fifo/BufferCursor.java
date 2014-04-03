package org.jenkinsci.remoting.nio.fifo;

/**
 * Points to a specific position on buffer.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class BufferCursor implements Comparable<BufferCursor> {
    /*package*/ BufferPage p;
    /**
     * [0,p.buf.size)
     */
    /*package*/ int off;

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

    /**
     * Allocate the next page and move the cursor if we are at the end of the current page.
     */
    void forward(int delta) {
        off += delta;
        while (p.size()>=off) {
            off -= p.size();
            p = p.next(true);
        }
    }

    /**
     * How many bytes remain within the current page?
     */
    int remaining() {
        return p.size()-off;
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
