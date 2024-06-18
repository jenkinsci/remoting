package org.jenkinsci.remoting.nio;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates {@link Closeable} that does socket half-close.
 *
 * @author Kohsuke Kawaguchi
 */
class Closeables {
    public static Closeable input(SelectableChannel ch) {
        if (ch instanceof SocketChannel) {
            final SocketChannel s = (SocketChannel) ch;
            return () -> {
                try {
                    s.socket().shutdownInput();
                } catch (IOException e) {
                    // at least as of Java7u55, shutdownInput fails if the socket
                    // is already closed or half-closed, as opposed to be a no-op.
                    // so let's just ignore close error altogether
                    LOGGER.log(Level.FINE, "Failed to close " + s, e);
                }
                maybeClose(s);
            };
        } else {
            return ch;
        }
    }

    public static Closeable output(SelectableChannel ch) {
        if (ch instanceof SocketChannel) {
            final SocketChannel s = (SocketChannel) ch;
            return () -> {
                try {
                    s.socket().shutdownOutput();
                } catch (IOException e) {
                    // see the discussion in try/catch block around shutdownInput above
                    LOGGER.log(Level.FINE, "Failed to close " + s, e);
                }
                maybeClose(s);
            };
        } else {
            return ch;
        }
    }

    /**
     * If both direction is closed, close the whole thing.
     */
    private static void maybeClose(SocketChannel sc) throws IOException {
        Socket s = sc.socket();
        if (s.isInputShutdown() && s.isOutputShutdown()) {
            s.close();
            sc.close();
        }
    }

    private static final Logger LOGGER = Logger.getLogger(Closeables.class.getName());
}
