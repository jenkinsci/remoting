package hudson.remoting.pipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Abstraction for creating and verifying data that goes over a input/output stream pair.
 *
 * @author Kohsuke Kawaguchi
 */
public interface Workload {
    void write(OutputStream o) throws IOException;

    void read(InputStream i) throws IOException;
}
