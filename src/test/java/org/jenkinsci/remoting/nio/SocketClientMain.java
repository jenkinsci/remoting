package org.jenkinsci.remoting.nio;

import hudson.remoting.Callable;
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
        Channel ch = new ChannelBuilder("client", es) {
            @Override
            protected CommandTransport makeTransport(InputStream is, OutputStream os, Mode mode, Capability cap) throws IOException {
                return super.makeTransport(
                        new ChunkedInputStream(is),
                        new ChunkedOutputStream(8192,os),
                        mode, cap);
            }
        }
                .withHeaderStream(new FlushEveryByteStream(System.out))
                .withMode(Mode.BINARY)
                .build(s);
        LOGGER.info("Established. Type some text to see it echoed back");

        final String arg = "Hello!";
        ch.call(new Callable<String,Exception>() {
            public String call() throws Exception {
                LOGGER.info("Echoing back "+arg);
                return arg;
            }
        });
        LOGGER.info("Got "+arg);


//        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
//        String line;
//        while ((line=r.readLine())!=null) {
//            final String arg = line;
//            ch.call(new Callable<String,Exception>() {
//                public String call() throws Exception {
//                    LOGGER.info("Echoing back "+arg);
//                    return arg;
//                }
//            });
//            LOGGER.info("Got "+arg);
//        }
    }

    private static final Logger LOGGER = Logger.getLogger(SocketClientMain.class.getName());
}
