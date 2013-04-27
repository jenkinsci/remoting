package hudson.remoting;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Remoting interface to allow the other side to retrieve a jar file
 * from the checksum advertised in {@link ResourceImageInJar}.
 *
 * <p>
 * {@link Channel} exposes this as {@linkplain Channel#getRemoteProperty(Object) a remote property}
 * under the key {@link #OURS}, then once retrieved we store it in a local property under {@link #THEIRS}.
 *
 * @author Kohsuke Kawaguchi
 */
public interface JarLoader {
    /**
     * Retrieve the jar file image.
     *
     * @param sink
     *      This stream receives the jar file.
     *
     * @throws InterruptedException
     *      Since this is a remote call, if the calling thread gets interrupted while waiting for the completion
     *      of the call, this exception will be thrown.
     */
    void writeJarTo(long sum1, long sum2, OutputStream sink) throws IOException, InterruptedException;

    final String OURS = JarLoader.class.getName()+".ours";
    final ChannelProperty<JarLoader> THEIRS = new ChannelProperty<JarLoader>(JarLoader.class,"their JarLoader");
}
