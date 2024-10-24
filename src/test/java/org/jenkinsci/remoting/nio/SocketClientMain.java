package org.jenkinsci.remoting.nio;

import hudson.remoting.CallableBase;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
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
        Socket s = new Socket("localhost", 9953);
        LOGGER.info("Cnonected");
        Channel ch = new ChannelBuilder("client", es)
                .withHeaderStream(new FlushEveryByteStream(System.out))
                .withMode(Channel.Mode.BINARY)
                .build(s);
        LOGGER.info("Established.");

        LOGGER.info("Got " + echo(ch, "Hello!"));

        ch.close();
        ch.join();
        es.shutdown();
    }

    private static String echo(Channel ch, final String arg) throws Exception {
        return ch.call(new EchoingCallable(arg));
    }

    private static final Logger LOGGER = Logger.getLogger(SocketClientMain.class.getName());

    private static class EchoingCallable extends CallableBase<String, Exception> {
        private final String arg;

        public EchoingCallable(String arg) {
            this.arg = arg;
        }

        @Override
        public String call() throws Exception {
            LOGGER.info("Echoing back " + arg);
            return arg;
        }

        private static final long serialVersionUID = 1L;
    }
}
