package fifo;

/**
 * Unit of buffer, singly linked and lazy created as needed.
 */
final class BufferPage {
    final byte[] buf;
    private volatile BufferPage next;

    /**
     * Sequential number.
     */
    final int n;


    BufferPage(int sz) {
        this.buf = new byte[sz];
        this.n = 0;
    }

    BufferPage(BufferPage prev) {
        this.buf = new byte[prev.size()];
        this.n = prev.n+1;
    }

    BufferPage next(boolean allocate) {
        if (next==null && allocate) {
            synchronized (this) {
                if (next==null && allocate) {
                    next = new BufferPage(this);
                }
            }
        }
        return next;
    }

    /**
     * Page size.
     */
    private int size() {
        return buf.length;
    }
}

