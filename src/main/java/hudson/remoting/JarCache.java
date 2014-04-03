package hudson.remoting;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;

/**
 * Jar file cache.
 *
 * <p>
 * The remoting library supports local jar file caching for the efficiency in remote class loading.
 * The cache stores jar files sent by the other side, and identifies jars with MD5 checksums that uniquely
 * identifies its content.
 *
 * This allows the cache to be reused by future channel sessions or other concurrent channel sessions.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.24
 */
public abstract class JarCache {
    /**
     * Looks up the jar in cache, and if not found, use {@link JarLoader} to retrieve it
     * from the other side.
     *
     * <p>
     * This method must be concurrency-safe.
     *
     * @param channel
     *      Channel that needs this jar file. Never null.
     * @return
     *      URL of the jar file. Must not be null.
     */
    public abstract Future<URL> resolve(Channel channel, long sum1, long sum2) throws IOException, InterruptedException;
}
