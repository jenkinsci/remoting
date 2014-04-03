package org.jenkinsci.remoting.nio;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;

/**
 * @author Kohsuke Kawaguchi
 */
class Closeables {
    public static Closeable input(SelectableChannel ch) {
        if (ch instanceof SocketChannel) {
            final SocketChannel s = (SocketChannel) ch;
            return new Closeable() {
                public void close() throws IOException {
                    s.socket().shutdownInput();
                }
            };
        } else
            return ch;
    }

    public static Closeable output(SelectableChannel ch) {
        if (ch instanceof SocketChannel) {
            final SocketChannel s = (SocketChannel) ch;
            return new Closeable() {
                public void close() throws IOException {
                    s.socket().shutdownOutput();
                }
            };
        } else
            return ch;
    }
}
