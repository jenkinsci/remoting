package hudson.remoting;

import hudson.remoting.Channel.Mode;
import org.jenkinsci.remoting.nio.NioChannelHub;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.channels.SelectionKey.OP_ACCEPT;
import static org.junit.Assert.*;

/**
 * Runs a channel over NIO+socket.
 */
public class NioSocketRunner implements ChannelRunner {
    private ExecutorService executor = Executors.newCachedThreadPool();
    private NioChannelHub nio;
    /**
     * failure occurred in the other {@link Channel}.
     */
    private Throwable failure;

    private Channel south;

    public Channel start() throws Exception {
        ServerSocketChannel ss = ServerSocketChannel.open();
        ss.configureBlocking(false);
        ss.socket().bind(null);

        nio = new NioChannelHub(115) {
            @Override
            protected void onSelected(SelectionKey key) {
                try {
                    ServerSocketChannel ss = (ServerSocketChannel) key.channel();
                    LOGGER.info("Acccepted");
                    final SocketChannel con = ss.accept();
                    executor.submit(new Runnable() {
                        public void run() {
                            try {
                                Socket socket = con.socket();
                                assertNull(south);
                                south = newChannelBuilder("south", executor)
                                        .withHeaderStream(System.out)
                                        .build(socket);
                                LOGGER.info("Connected to " + south);
                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING, "Handshake failed", e);
                                failure = e;
                            }
                        }
                    });
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to accept a socket",e);
                    failure = e;
                }
            }
        };
        ss.register(nio.getSelector(), OP_ACCEPT);
        LOGGER.info("Waiting for connection");
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

        // create a client channel that connects to the same hub
        SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", ss.socket().getLocalPort()));
        return nio.newChannelBuilder("north",executor).withMode(Mode.BINARY).build(client);
    }

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

    public String getName() {
        return "NIO+socket";
    }

    private static final Logger LOGGER = Logger.getLogger(NioSocketRunner.class.getName());
}
