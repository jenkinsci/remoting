package hudson.remoting.throughput;

import hudson.remoting.Channel;
import hudson.remoting.SocketInputStream;
import hudson.remoting.SocketOutputStream;

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
        ServerSocket ss = new ServerSocket(PORT);
        while (true) {
            System.out.println("Ready");
            Socket s = ss.accept();
            s.setTcpNoDelay(true);
            System.out.println("Accepted");
            Channel ch = new Channel("bogus", Executors.newCachedThreadPool(),
                    new SocketInputStream(s),new SocketOutputStream(s));
            ch.join();
            s.close();
        }
    }
    public static final int PORT = 9532;
}