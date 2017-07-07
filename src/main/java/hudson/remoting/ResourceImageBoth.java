package hudson.remoting;

import java.io.IOException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

/**
 * @author Kohsuke Kawaguchi
 */
class ResourceImageBoth extends ResourceImageDirect {
    final long sum1,sum2;
    final boolean useFileCachesInJarUrlConnection;

    public ResourceImageBoth(URL resource, Checksum sum) throws IOException {
        this(resource, sum, true);
    }
    
    public ResourceImageBoth(URL resource, Checksum sum, boolean useFileCachesInJarUrlConnection) throws IOException {
        super(resource);
        this.sum1 = sum.sum1;
        this.sum2 = sum.sum2;
        this.useFileCachesInJarUrlConnection = useFileCachesInJarUrlConnection;
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
            return new ResourceImageInJar(sum1,sum2,null,useFileCachesInJarUrlConnection).resolveURL(channel,resourcePath);
        else
            return super.resolveURL(channel, resourcePath);
    }

    /**
     * Starts JAR retrieval over the channel.
     * 
     * @param channel Channel instance
     * @return Future object. In the case of error the diagnostics info will be sent to {@link #LOGGER}.
     */
    @Nonnull
    private Future<URL> initiateJarRetrieval(@Nonnull Channel channel) throws IOException, InterruptedException {
        JarCache c = channel.getJarCache();
        assert c !=null : "we don't advertise jar caching to the other side unless we have a cache with us";

        try {
            return c.resolve(channel, sum1, sum2);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to initiate retrieval", e);
            throw e;
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Failed to initiate retrieval", e);
            Thread.currentThread().interrupt(); // process the interrupt later
            throw e;
        }
    }

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(ResourceImageBoth.class.getName());
}
