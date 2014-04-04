package hudson.remoting;

import hudson.remoting.Channel.Mode;
import org.jenkinsci.remoting.nio.NioChannelHub;

import java.io.IOException;
import java.nio.channels.Pipe;
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
                    south = nio.newChannelBuilder("south",executor).withMode(Mode.NEGOTIATE)
                            .build(n2s.source(), s2n.sink());
                    south.join();
                    System.out.println("south completed");
                } catch (IOException e) {
                    e.printStackTrace();
                    failure = e;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    failure = e;
                }
            }
        });

        // create a client channel that connects to the same hub
        return nio.newChannelBuilder("north",executor).withMode(Mode.BINARY)
                .build(s2n.source(), n2s.sink());
    }

    public String getName() {
        return "NIO+pipe";
    }

    private static final Logger LOGGER = Logger.getLogger(NioSocketRunner.class.getName());
}

