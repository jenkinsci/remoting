package org.jenkinsci.remoting.nio;

import hudson.remoting.Channel;
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

/**
 * @author Kohsuke Kawaguchi
 */
public class SocketServerMain {
    public static void main(String[] args) throws Exception {
        final ExecutorService es = Executors.newCachedThreadPool();

        ServerSocketChannel ss = ServerSocketChannel.open();
        ss.configureBlocking(false);
        ss.socket().bind(new InetSocketAddress(9953));

        NioChannelHub nio = new NioChannelHub(es) {
            @Override
            protected void onSelected(SelectionKey key) {
                try {
                    ServerSocketChannel ss = (ServerSocketChannel) key.channel();
                    LOGGER.info("Acccepted");
                    final SocketChannel con = ss.accept();
                    es.submit(() -> {
                        try {
                            // TODO: this is where we do more config
                            Socket socket = con.socket();
                            // TODO: does this actually produce async channel?
                            Channel ch = newChannelBuilder(con.toString(), es)
                                    .withHeaderStream(new FlushEveryByteStream(System.out))
                                    .build(socket);
                            LOGGER.info("Connected to " + ch);
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Handshake failed", e);
                        }
                    });
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to accept a soccket", e);
                }
            }
        };
        ss.register(nio.getSelector(), SelectionKey.OP_ACCEPT);
        LOGGER.info("Waiting for connection");
        nio.run();
    }

    private static final Logger LOGGER = Logger.getLogger(SocketServerMain.class.getName());
}
