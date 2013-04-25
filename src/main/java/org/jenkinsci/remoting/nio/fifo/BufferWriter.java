package org.jenkinsci.remoting.nio.fifo;

/**
 * @author Kohsuke Kawaguchi
 */
public class BufferWriter extends BufferCursor {
    public BufferWriter(int pageSize) {
        super(new BufferPage(pageSize),0);
    }

    @Override
    public BufferWriter getWriter() {
        return this;
    }

//    public void write(byte[] buf, int start, int len) {
//        while (len>0) {
//            int chunk = Math.min(len,chunk());
//            System.arraycopy(buf,start,p.buf,off,chunk);
//
//            off+=chunk;
//            len-=chunk;
//            start+=chunk;
//        }
//    }

}
