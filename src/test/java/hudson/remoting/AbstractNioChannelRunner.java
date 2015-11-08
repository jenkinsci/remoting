package hudson.remoting;

import org.jenkinsci.remoting.nio.NioChannelHub;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertTrue;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class AbstractNioChannelRunner implements DualSideChannelRunner {
    protected ExecutorService executor = Executors.newCachedThreadPool();
    protected NioChannelHub nio;
    /**
     * failure occurred in the other {@link Channel}.
     */
    protected Throwable failure;

    protected Channel south;


    public void stop(Channel channel) throws Exception {
        channel.close();
        channel.join();

        System.out.println("north completed");

        // we initiate the shutdown from north, so by the time it closes south should be all closed, too
        assertTrue(south.isInClosed());
        assertTrue(south.isOutClosed());

        nio.close();
        executor.shutdown();

        if(failure!=null)
            throw new AssertionError(failure);  // report a failure in the south side
    }

    @Override
    public Channel getOtherSide() {
        return south;
    }
}
