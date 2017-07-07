package hudson.remoting;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;

/**
 * {@link ResourceImageRef} that points to a resource inside a jar file.
 *
 * <p>
 * The jar file is identified by its checksum, and the receiver can use {@link JarLoader}
 * to retrieve the jar file if necessary.
 *
 * @author Kohsuke Kawaguchi
 */
class ResourceImageInJar extends ResourceImageRef {
    /**
     * Check sum of the jar file that contains the resource.
     */
    final long sum1,sum2;

    /**
     * This field is null if the resource being requested is in the 'sane' location inside the jar file
     * (the path that {@link URLClassLoader} will find when given a jar file.) Otherwise this specifies
     * the exact path inside the jar file of the resource.
     */
    final String path;
    
    final boolean useCaches;

    ResourceImageInJar(long sum1, long sum2, String path, boolean useCaches) {
        this.sum1 = sum1;
        this.sum2 = sum2;
        this.path = path;
        this.useCaches = useCaches;
    }
    
    ResourceImageInJar(long sum1, long sum2, String path) {
        this(sum1, sum2, path, true);
    }
    
    ResourceImageInJar(Checksum sum, String path, boolean useCaches) {
        this(sum.sum1, sum.sum2, path, useCaches);
    }

    ResourceImageInJar(Checksum sum, String path) {
        this(sum.sum1,sum.sum2,path);
    }

    @Override
    Future<byte[]> resolve(Channel channel, final String resourcePath) throws IOException, InterruptedException {
        return new FutureAdapter<byte[],URL>(_resolveJarURL(channel)) {
            @Override
            protected byte[] adapt(URL jar) throws ExecutionException {
                final URLConnection c;
                try {
                    URL url = toResourceURL(jar,resourcePath);
                    c = url.openConnection();
                    if (!useCaches) {
                        // Disable caching if requested
                        c.setUseCaches(false);
                    }
                } catch (IOException ex) {
                    throw new ExecutionException(ex);
                }
                
                try(InputStream istream = c.getInputStream()) {
                    return Util.readFully(istream);
                } catch (IOException e) {
                    throw new ExecutionException(e);
                }
            }
        };
    }

    @Override
    Future<URLish> resolveURL(Channel channel, final String resourcePath) throws IOException, InterruptedException {
        return new FutureAdapter<URLish,URL>(_resolveJarURL(channel)) {
            @Override
            protected URLish adapt(URL jar) throws ExecutionException {
                try {
                    return URLish.from(toResourceURL(jar, resourcePath));
                } catch (IOException e) {
                    throw new ExecutionException(e);
                }
            }
        };
    }

    @Nonnull
    private URL toResourceURL(URL jar, String resourcePath) throws IOException {
        if (path!=null)
            resourcePath = path;
        /*
            James Nord & Kohsuke:
                Note that when we open a stream from this jar:// URL, it internally caches
                open jar file (see sun.net.www.protocol.jar.JarURLConnection.JarURLInputStream.close())
                and leave it open. During unit test, this pins the file, and it prevents the test tear down code
                from deleting the file.
            Oleg Nenashev:
                It does not happen if internal caching is disabled
         */
        return new URL("jar:"+ jar +"!/"+resourcePath);
    }

    Future<URL> _resolveJarURL(Channel channel) throws IOException, InterruptedException {
        JarCache c = channel.getJarCache();
        assert c !=null : "we don't advertise jar caching to the other side unless we have a cache with us";

        return c.resolve(channel, sum1, sum2);
//            throw (IOException)new IOException(String.format("Failed to resolve a jar %016x%016x",sum1,sum2)).initCause(e);
    }

    private static final long serialVersionUID = 1L;
}
