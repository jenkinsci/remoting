package hudson.remoting;

import hudson.remoting.Channel.Mode;
import org.jenkinsci.remoting.nio.NioChannelHub;

import java.nio.channels.Pipe;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a channel over NIO {@link java.nio.channels.Pipe}.
 *
 * <p>
 * This exercises {@link NioChannelHub} differently because it has different channel
 * objects for read end and the write end.
 *
 * @author Kohsuke Kawaguchi
 */
public class NioPipeRunner extends AbstractNioChannelRunner {
    public Channel start() throws Exception {
        final SynchronousQueue<Channel> southHandoff = new SynchronousQueue<Channel>();

        final java.nio.channels.Pipe n2s = Pipe.open();
        final java.nio.channels.Pipe s2n = Pipe.open();

        nio = new NioChannelHub(executor);
        nio.setFrameSize(132);  // force unaligned boundaries to shake things up a bit

        executor.submit(new Runnable() {
            public void run() {
                try {
                    nio.run();
                } catch (Throwable e) {
                    LOGGER.log(Level.WARNING, "Faield to keep the NIO selector thread going",e);
                    failure = e;
                }
            }
        });
        executor.submit(new Runnable() {
            public void run() {
                try {
                    Channel south = nio.newChannelBuilder("south",executor).withMode(Mode.NEGOTIATE)
                            .build(n2s.source(), s2n.sink());
                    southHandoff.put(south);
                    south.join();
                    System.out.println("south completed");
                } catch (Exception e) {
                    e.printStackTrace();
                    failure = e;
                }
            }
        });

        // create a client channel that connects to the same hub
        Channel north = nio.newChannelBuilder("north", executor).withMode(Mode.BINARY)
                .build(s2n.source(), n2s.sink());
        south = southHandoff.poll(10, TimeUnit.SECONDS);
        return north;
    }

    public String getName() {
        return "NIO+pipe";
    }

    private static final Logger LOGGER = Logger.getLogger(NioSocketRunner.class.getName());
}

