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
 * @since 2.24
 */
public interface JarLoader {
    /**
     * Retrieve the jar file image.
     *
     * This method is called by the other side to receive the jar file. This call implicitly
     * has the effect of {@link #notifyJarPresence(long, long)}
     *
     * @param sink
     *      This stream receives the jar file.
     *
     * @throws InterruptedException
     *      Since this is a remote call, if the calling thread gets interrupted while waiting for the completion
     *      of the call, this exception will be thrown.
     */
    void writeJarTo(long sum1, long sum2, OutputStream sink) throws IOException, InterruptedException;

    /**
     * Called by the other side to notify that they already own the jar file of the given checksum.
     *
     * This allows this side to send {@link ResourceImageRef} smartly by avoiding unnecessary
     * image transport.
     */
    @Asynchronous
    void notifyJarPresence(long sum1, long sum2);

    /**
     * @param sums
     *      Array of even length. sums[2i] and sumes[2i+1] are paired up and interpreted as one checksum.
     */
    @Asynchronous
    void notifyJarPresence(long[] sums);

    /**
     * Used by the local side to see if the jar file of the given checksum is already present
     * on the other side. Used to decide if the class file image gets sent to the remote or not.
     */
    boolean isPresentOnRemote(Checksum sum);

    String OURS = JarLoader.class.getName() + ".ours";
    ChannelProperty<JarLoader> THEIRS = new ChannelProperty<>(JarLoader.class, "their JarLoader");
}
