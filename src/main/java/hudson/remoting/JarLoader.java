package hudson.remoting;

import java.io.IOException;
import java.io.OutputStream;

/**
 * @author Kohsuke Kawaguchi
 */
public interface JarLoader {
    /**
     *
     * @throws InterruptedException
     *      Since this is a remote call, if the calling thread gets interrupted while waiting for the completion
     *      of the call, this exception will be thrown.
     */
    void writeJarTo(long sum1, long sum2, OutputStream sink) throws IOException, InterruptedException;

    final String OURS = JarLoader.class.getName()+".ours";
    final ChannelProperty<JarLoader> THEIRS = new ChannelProperty<JarLoader>(JarLoader.class,"their JarLoader");
}
