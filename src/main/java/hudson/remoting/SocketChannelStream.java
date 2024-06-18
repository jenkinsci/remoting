package hudson.remoting;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wraps {@link SocketChannel} into {@link InputStream}/{@link OutputStream} in a way
 * that avoids deadlock when read/write happens concurrently.
 *
 * @author Kohsuke Kawaguchi
 * @see <a href="http://stackoverflow.com/questions/174774/">discussion with references to JDK bugs; TODO JDK-8222774 simplify in Java 17</a>
 */
public class SocketChannelStream {
    public static InputStream in(Socket s) throws IOException {
        if (s.getChannel() != null) {
            return in(s.getChannel());
        } else {
            return new SocketInputStream(s);
        }
    }

    public static InputStream in(final SocketChannel ch) throws IOException {
        final Socket s = ch.socket();

        return Channels.newInputStream(new ReadableByteChannel() {
            @Override
            public int read(ByteBuffer dst) throws IOException {
                return ch.read(dst);
            }

            @Override
            public void close() throws IOException {
                if (!s.isInputShutdown()) {
                    try {
                        s.shutdownInput();
                    } catch (IOException e) {
                        LOGGER.log(Level.FINE, "Failed to shutdownInput", e);
                    }
                }
                if (s.isOutputShutdown()) {
                    ch.close();
                    s.close();
                }
            }

            @Override
            public boolean isOpen() {
                return !s.isInputShutdown();
            }
        });
    }

    public static OutputStream out(Socket s) throws IOException {
        if (s.getChannel() != null) {
            return out(s.getChannel());
        } else {
            return new SocketOutputStream(s);
        }
    }

    public static OutputStream out(final SocketChannel ch) throws IOException {
        final Socket s = ch.socket();

        return Channels.newOutputStream(new WritableByteChannel() {
            @Override
            public int write(ByteBuffer src) throws IOException {
                return ch.write(src);
            }

            @Override
            public void close() throws IOException {
                if (!s.isOutputShutdown()) {
                    try {
                        s.shutdownOutput();
                    } catch (IOException e) {
                        LOGGER.log(Level.FINE, "Failed to shutdownOutput", e);
                    }
                }
                if (s.isInputShutdown()) {
                    ch.close();
                    s.close();
                }
            }

            @Override
            public boolean isOpen() {
                return !s.isOutputShutdown();
            }
        });
    }

    private static final Logger LOGGER = Logger.getLogger(SocketChannelStream.class.getName());
}
