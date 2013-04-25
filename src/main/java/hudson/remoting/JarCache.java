package hudson.remoting;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class JarCache {
    /**
     *
     * <p>
     * This method must be concurrency-safe.
     */
    public abstract URL resolve(Channel channel, long sum1, long sum2) throws IOException, InterruptedException;


}
