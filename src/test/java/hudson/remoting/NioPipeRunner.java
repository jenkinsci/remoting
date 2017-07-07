package hudson.remoting;

import hudson.remoting.Channel.Mode;
import org.jenkinsci.remoting.nio.NioChannelHub;

import java.nio.channels.Pipe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.nio.NioChannelBuilder;
import org.jenkinsci.remoting.protocol.impl.ChannelApplicationLayer.ChannelDecorator;

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
    
    private final List<ChannelDecorator> channelDecorators = new ArrayList<>();
    
    public void addChannnelDecorator(ChannelDecorator decorator) {
        channelDecorators.add(decorator);
    }
    
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
                    NioChannelBuilder southBuilder = nio.newChannelBuilder("south",executor).withMode(Mode.NEGOTIATE);
                    for (ChannelDecorator d : channelDecorators) {
                        southBuilder = (NioChannelBuilder)d.decorate(southBuilder);
                    } 
                    Channel south = southBuilder.build(n2s.source(), s2n.sink());
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
        NioChannelBuilder northBuilder = nio.newChannelBuilder("north", executor).withMode(Mode.BINARY);
        for (ChannelDecorator d : channelDecorators) {
            northBuilder = (NioChannelBuilder)d.decorate(northBuilder);
        }
        Channel north = northBuilder.build(s2n.source(), n2s.sink());
        south = southHandoff.poll(10, TimeUnit.SECONDS);
        return north;
    }

    public String getName() {
        return "NIO+pipe";
    }

    private static final Logger LOGGER = Logger.getLogger(NioSocketRunner.class.getName());
}

