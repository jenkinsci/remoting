package org.jenkinsci.remoting.nio;

import hudson.remoting.Callable;
import hudson.remoting.CallableBase;
import hudson.remoting.Capability;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Mode;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.CommandTransport;
import hudson.remoting.SocketInputStream;
import hudson.remoting.SocketOutputStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
        LOGGER.info("Established.");

        LOGGER.info("Got "+echo(ch,"Hello!"));

        ch.close();
        ch.join();
        es.shutdown();
    }

    private static String echo(Channel ch, final String arg) throws Exception {
        return ch.call(new CallableBase<String, Exception>() {
            public String call() throws Exception {
                LOGGER.info("Echoing back "+arg);
                return arg;
            }
        });
    }

    private static final Logger LOGGER = Logger.getLogger(SocketClientMain.class.getName());
}
