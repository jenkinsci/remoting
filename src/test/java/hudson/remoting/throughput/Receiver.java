package hudson.remoting.throughput;

import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.SocketChannelStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;

/**
 * Accepts a channel one at a time.
 *
 * @author Kohsuke Kawaguchi
 */
public class Receiver {
    public static void main(String[] args) throws Exception {
        try (ServerSocket ss = new ServerSocket(PORT)) {
            while (true) {
                System.out.println("Ready");
                try (Socket s = ss.accept()) {
                    s.setTcpNoDelay(true);
                    System.out.println("Accepted");
                    Channel ch = new ChannelBuilder("bogus", Executors.newCachedThreadPool())
                            .build(
                                    new BufferedInputStream(SocketChannelStream.in(s)),
                                    new BufferedOutputStream(SocketChannelStream.out(s)));
                    ch.join();
                }
            }
        }
    }

    public static final int PORT = 9532;
}
