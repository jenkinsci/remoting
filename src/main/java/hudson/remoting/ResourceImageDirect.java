package hudson.remoting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ResourceImageRef} that directly encapsulates the resource as {@code byte[]}.
 *
 * <p>
 * This is used when {@link ResourceImageInJar} cannot be used because we couldn't identify the jar file.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressFBWarnings(
        value = "URLCONNECTION_SSRF_FD",
        justification = "Used by the agent as part of jar cache management.")
class ResourceImageDirect extends ResourceImageRef {
    /**
     * The actual resource.
     */
    private final byte[] payload;

    ResourceImageDirect(byte[] payload) {
        this.payload = payload;
    }

    ResourceImageDirect(URL resource) throws IOException {
        this(Util.readFully(resource.openStream()));
    }

    @Override
    Future<byte[]> resolve(Channel channel, String resourcePath) throws IOException, InterruptedException {
        LOGGER.log(Level.FINE, resourcePath + " image is direct");
        return CompletableFuture.completedFuture(payload);
    }

    @Override
    Future<URLish> resolveURL(Channel channel, String resourcePath) throws IOException, InterruptedException {
        return CompletableFuture.completedFuture(URLish.from(Util.makeResource(resourcePath, payload)));
    }

    private static final Logger LOGGER = Logger.getLogger(ResourceImageDirect.class.getName());

    private static final long serialVersionUID = 1L;
}
