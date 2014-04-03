package org.jenkinsci.remoting.nio.fifo;

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

    public int read(byte[] buf, int start, int len) {
        int sz=0;
        while (len>0) {
            int chunk = Math.min(len, remaining());
            if (writer.p==this.p) {
                // are we at the tail end? if so we can only read up to where the writer is.
                chunk = Math.min(chunk, writer.off-this.off);
                if (chunk==0) {
                    if (sz==0)  return -1;  // EOF
                    return sz;
                }
            }
            System.arraycopy(p.buf,off,buf,start,chunk);

            forward(chunk);

            sz += chunk;
            len-=chunk;
            start+=chunk;
        }

        return sz;
    }

}
