package hudson.remoting;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import javax.annotation.Nonnull;

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
     * Default JAR cache location for disabled workspace Manager.
     */
    public static final File DEFAULT_NOWS_JAR_CACHE_LOCATION =
        new File(System.getProperty("user.home"),".jenkins/cache/jars");

    //TODO: replace by checked exception
    /**
     * Gets a default value for {@link FileSystemJarCache} to be initialized on agents.
     * @return Created JAR Cache
     * @throws IOException Default JAR Cache location cannot be initialized
     */
    @Nonnull
    /*package*/ static JarCache getDefault() throws IOException {
        try {
            return new FileSystemJarCache(DEFAULT_NOWS_JAR_CACHE_LOCATION, true);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Failed to initialize the default JAR Cache location", ex);
        }
    }

    /**
     * Looks up the jar in cache, and if not found, use {@link JarLoader} to retrieve it
     * from the other side.
     *
     * <p>
     * This method must be concurrency-safe.
     *
     * @param channel
     *      Channel that needs this jar file.
     * @return
     *      URL of the jar file.
     */
    @Nonnull
    public abstract Future<URL> resolve(@Nonnull Channel channel, long sum1, long sum2) throws IOException, InterruptedException;
}
