package org.jenkinsci.remoting.nio;

import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Mode;
import hudson.remoting.ChannelBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * @author Kohsuke Kawaguchi
 */
public class SocketClientMain {
    public static void main(String[] args) throws Exception {
        final ExecutorService es = Executors.newCachedThreadPool();
        Socket s = new Socket("localhost",9953);
        LOGGER.info("Cnonected");
        Channel ch = new ChannelBuilder("client", es)
                .withHeaderStream(new FlushEveryByteStream(System.out))
                .withMode(Mode.BINARY)
                .build(s);
        LOGGER.info("Established");

        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line=r.readLine())!=null) {
            final String arg = line;
            ch.call(new Callable<String,Exception>() {
                public String call() throws Exception {
                    LOGGER.info("Echoing back "+arg);
                    return arg;
                }
            });
            LOGGER.info("Got "+arg);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(SocketClientMain.class.getName());
}
