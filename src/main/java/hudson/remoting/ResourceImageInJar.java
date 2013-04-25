package hudson.remoting;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * @author Kohsuke Kawaguchi
 */
class ResourceImageInJar extends ResourceImageRef {
    /**
     * Check sum of the jar file.
     */
    private final long sum1,sum2;

    /**
     * This field is null if the resource being requested is in the 'sane' location inside the jar file
     * (the path that {@link URLClassLoader} will find when given a jar file.) Otherwise this specifies
     * the exact path inside the jar file of the resource.
     */
    private final String path;

    ResourceImageInJar(long sum1, long sum2, String path) {
        this.sum1 = sum1;
        this.sum2 = sum2;
        this.path = path;
    }

    ResourceImageInJar(Checksum sum, String path) {
        this(sum.sum1,sum.sum2,path);
    }

    @Override
    byte[] resolve(Channel channel, String resourcePath) throws IOException, InterruptedException {
        return Util.readFully(_resolveURL(channel,resourcePath).openStream());
    }

    URL _resolveURL(Channel channel, String resourcePath) throws IOException, InterruptedException {
        JarCache c = channel.getJarCache();
        assert c !=null : "we don't advertise jar caching to the other side unless we have a cache with us";
        URL jar = c.resolve(channel, sum1, sum2);

        if (path!=null)
            resourcePath = path;

        return new URL("jar:"+jar+"!/"+resourcePath);
    }

    @Override
    URLish resolveURL(Channel channel, String resourcePath) throws IOException, InterruptedException {
        return URLish.from(_resolveURL(channel,resourcePath));
    }

    private static final long serialVersionUID = 1L;
}
