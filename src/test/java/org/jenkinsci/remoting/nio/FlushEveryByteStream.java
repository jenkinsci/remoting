package org.jenkinsci.remoting.nio;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * @author Kohsuke Kawaguchi
 */
public class FlushEveryByteStream extends FilterOutputStream {
    public FlushEveryByteStream(OutputStream out) {
        super(out);
    }

    @Override
    public void write(int b) throws IOException {
        super.write(b);
        flush();
    }
}
