package hudson.remoting;

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.Future;

/**
 * Wire protocol data representation that encapsulates the access to a resource inside a {@link ClassLoader}.
 *
 * <p>
 * We support two kinds of references. One {@linkplain ResourceImageInJar points to a resource inside a jar},
 * and the other {@linkplain ResourceImageDirect directly contains the byte[] image}.
 *
 * <p>
 * Subtypes need to be available on both sides of the channel, so it's package protected.
 *
 * @author Kohsuke Kawaguchi
 */
/*package*/ abstract class ResourceImageRef implements Serializable {
    /**
     * Obtains the a resource file byte image.
     *
     * @param channel
     *      The channel object as the context.
     * @param resourcePath
     *      Fully qualified name of the resource being retrieved, that doesn't start with '/',
     *      such as 'java/lang/String.class' Identical to the name parameter in {@link ClassLoader#getResource(String)}.
     *
     *      This parameter must be the same name you used to retrieve {@link RemoteClassLoader.ClassReference}.
     *
     *      One {@link ResourceImageRef} represents a single resource, and that resource name
     *      is specified when you retrieve {@link RemoteClassLoader.ClassReference}. Therefore from pure abstraction
     *      point of view, this information is redundant. However, specifying that information reduces
     *      the amount of state {@link ResourceImageRef}s need to carry around, which helps reduce
     *      the bandwidth consumption.
     */
    /*package*/ abstract Future<byte[]> resolve(Channel channel, String resourcePath)
            throws IOException, InterruptedException;

    /**
     * Returns an URL that points to this resource.
     *
     * This may require creating a temporary file.
     */
    /*package*/ abstract Future<URLish> resolveURL(Channel channel, String resourcePath)
            throws IOException, InterruptedException;

    private static final long serialVersionUID = 1L;
}
