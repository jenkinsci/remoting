package hudson.remoting;

import java.nio.channels.Pipe;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.nio.NioChannelHub;

/**
 * Runs a channel over NIO {@link Pipe}.
 *
 * <p>
 * This exercises {@link NioChannelHub} differently because it has different channel
 * objects for read end and the write end.
 *
 * @author Kohsuke Kawaguchi
 */
public class NioPipeRunner extends AbstractNioChannelRunner {
    @Override
    public Channel start() throws Exception {
        final SynchronousQueue<Channel> southHandoff = new SynchronousQueue<>();

        final Pipe n2s = Pipe.open();
        final Pipe s2n = Pipe.open();

        nio = new NioChannelHub(executor);
        nio.setFrameSize(132); // force unaligned boundaries to shake things up a bit

        executor.submit(() -> {
            try {
                nio.run();
            } catch (Throwable e) {
                LOGGER.log(Level.WARNING, "Faield to keep the NIO selector thread going", e);
                failure = e;
            }
        });
        executor.submit(() -> {
            try {
                Channel south = nio.newChannelBuilder("south", executor)
                        .withMode(Channel.Mode.NEGOTIATE)
                        .build(n2s.source(), s2n.sink());
                southHandoff.put(south);
                south.join();
                System.out.println("south completed");
            } catch (Exception e) {
                e.printStackTrace();
                failure = e;
            }
        });

        // create a client channel that connects to the same hub
        Channel north = nio.newChannelBuilder("north", executor)
                .withMode(Channel.Mode.BINARY)
                .build(s2n.source(), n2s.sink());
        south = southHandoff.poll(10, TimeUnit.SECONDS);
        return north;
    }

    @Override
    public String getName() {
        return "NIO+pipe";
    }

    private static final Logger LOGGER = Logger.getLogger(NioSocketRunner.class.getName());
}
