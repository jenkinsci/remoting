package hudson.remoting;

import java.io.IOException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Kohsuke Kawaguchi
 */
class ResourceImageBoth extends ResourceImageDirect {
    final long sum1,sum2;

    public ResourceImageBoth(URL resource, Checksum sum) throws IOException {
        super(resource);
        this.sum1 = sum.sum1;
        this.sum2 = sum.sum2;
    }

    @Override
    Future<byte[]> resolve(Channel channel, String resourcePath) throws IOException, InterruptedException {
        initiateJarRetrieval(channel);
        return super.resolve(channel, resourcePath);
    }

    @Override
    Future<URLish> resolveURL(Channel channel, String resourcePath) throws IOException, InterruptedException {
        Future<URL> f = initiateJarRetrieval(channel);
        if (f.isDone()) // prefer using the jar URL if the stuff is already available
            return new ResourceImageInJar(sum1,sum2,null).resolveURL(channel,resourcePath);
        else
            return super.resolveURL(channel, resourcePath);
    }

    private Future<URL> initiateJarRetrieval(Channel channel) {
        JarCache c = channel.getJarCache();
        assert c !=null : "we don't advertise jar caching to the other side unless we have a cache with us";

        try {
            return c.resolve(channel, sum1, sum2);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to initiate retrieval", e);
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Failed to initiate retrieval", e);
            Thread.currentThread().interrupt(); // process the interrupt later
        }
        return null;
    }

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(ResourceImageBoth.class.getName());
}
