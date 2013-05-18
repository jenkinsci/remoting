package hudson.remoting;

import java.io.IOException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Kohsuke Kawaguchi
 */
public class ResourceImageBoth extends ResourceImageDirect {
    final long sum1,sum2;

    public ResourceImageBoth(URL resource, Checksum sum) throws IOException {
        super(resource);
        this.sum1 = sum.sum1;
        this.sum2 = sum.sum2;
    }

    @Override
    Future<byte[]> resolve(Channel channel, String resourcePath) throws IOException {
        initiateJarRetrieval(channel);
        return super.resolve(channel, resourcePath);
    }

    @Override
    Future<URLish> resolveURL(Channel channel, String resourcePath) throws IOException {
        // TODO: initiate downloading of the jar
        return super.resolveURL(channel, resourcePath);
    }

    private void initiateJarRetrieval(Channel channel) {
        JarCache c = channel.getJarCache();
        assert c !=null : "we don't advertise jar caching to the other side unless we have a cache with us";

        try {
            c.resolve(channel, sum1, sum2);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to initiate retrieval", e);
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Failed to initiate retrieval", e);
            Thread.currentThread().interrupt(); // process the interrupt later
        }
    }

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(ResourceImageBoth.class.getName());
}
