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

/**
 * Wraps {@link SocketChannel} into {@link InputStream}/{@link OutputStream} in a way
 * that avoids deadlock when read/write happens concurrently.
 *
 * @author Kohsuke Kawaguchi
 * @see <a href="http://stackoverflow.com/questions/174774/">discussion with references to BugParade Bug IDs</a>
 */
class SocketChannelStream {
    static InputStream getInputStream(final SocketChannel ch) throws IOException {
        final Socket s = ch.socket();

        return Channels.newInputStream(new ReadableByteChannel() {
            public int read(ByteBuffer dst) throws IOException {
                return ch.read(dst);
            }

            public void close() throws IOException {
                s.shutdownInput();
                if (s.isOutputShutdown()) {
                    ch.close();
                    s.close();
                }
            }

            public boolean isOpen() {
                return !s.isInputShutdown();
            }
        });
    }

    static OutputStream getOutputStream(final SocketChannel ch) throws IOException {
        final Socket s = ch.socket();

        return Channels.newOutputStream(new WritableByteChannel() {
            public int write(ByteBuffer src) throws IOException {
                return ch.write(src);
            }

            public void close() throws IOException {
                s.shutdownOutput();
                if (s.isInputShutdown()) {
                    ch.close();
                    s.close();
                }
            }

            public boolean isOpen() {
                return !s.isOutputShutdown();
            }
        });
    }
}
