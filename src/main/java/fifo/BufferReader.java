package fifo;

/**
 * @author Kohsuke Kawaguchi
 */
public class BufferReader extends BufferCursor {
    private final BufferWriter writer;

    public BufferReader(BufferCursor src) {
        super(src);
        this.writer = src.getWriter();
    }

    @Override
    public BufferWriter getWriter() {
        return writer;
    }
}
