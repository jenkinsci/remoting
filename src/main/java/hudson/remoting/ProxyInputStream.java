/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.util.IOUtils;

/**
 * {@link InputStream} that reads bits from an exported
 * {@link InputStream} on a remote machine.
 *
 * <p>
 * TODO: pre-fetch bytes in advance
 *
 * @author Kohsuke Kawaguchi
 */
final class ProxyInputStream extends InputStream {

    private static final Logger LOGGER = Logger.getLogger(ProxyInputStream.class.getName());

    private Channel channel;
    private int oid;

    /**
     * Creates an already connected {@link ProxyOutputStream}.
     *
     * @param oid
     *      The object id of the exported {@link OutputStream}.
     */
    public ProxyInputStream(Channel channel, int oid) throws IOException {
        this.channel = channel;
        this.oid = oid;
    }

    @Override
    public int read() throws IOException {
        try {
            Buffer buf = _read(1);
            if (buf.len == 1) {
                // byte->int expansion needs to be done carefully becaue byte in Java is signed
                // whose idea was it to make byte signed, anyway!?
                return ((int) buf.buf[0]) & 0xFF;
            } else {
                return -1;
            }
        } catch (InterruptedException e) {
            // pretend EOF
            Thread.currentThread().interrupt(); // process interrupt later
            close();
            return -1;
        }
    }

    private synchronized Buffer _read(int len) throws IOException, InterruptedException {
        return new Chunk(oid, len).call(channel);
    }

    @Override
    public int read(@NonNull byte[] b, int off, int len) throws IOException {
        try {
            Buffer buf = _read(len);
            if (buf.len == -1) {
                return -1;
            }
            System.arraycopy(buf.buf, 0, b, off, buf.len);
            return buf.len;
        } catch (InterruptedException e) {
            // pretend EOF
            Thread.currentThread().interrupt(); // process interrupt later
            close();
            return -1;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (channel != null) {
            channel.send(new EOF(oid));
            channel = null;
            oid = -1;
        }
    }

    private static final class Buffer implements Serializable {
        byte[] buf;
        int len;

        public Buffer(int len) {
            this.buf = new byte[len];
        }

        public void read(InputStream in) throws IOException {
            len = in.read(buf, 0, buf.length);
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * Command to fetch bytes.
     */
    private static final class Chunk extends Request<Buffer, IOException> {
        private final int oid;
        private final int len;

        public Chunk(int oid, int len) {
            this.oid = oid;
            this.len = len;
        }

        @Override
        protected Buffer perform(Channel channel) throws IOException {
            InputStream in;
            try {
                in = (InputStream) channel.getExportedObject(oid);
            } catch (ExecutionException ex) {
                throw new IOException(ex);
            }

            Buffer buf = new Buffer(len);
            buf.read(in);
            return buf;
        }

        @Override
        public String toString() {
            return "ProxyInputStream.Chunk(len=" + len + ")";
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link Command} for sending EOF.
     */
    private static final class EOF extends Command {
        private final int oid;

        public EOF(int oid) {
            this.oid = oid;
        }

        @Override
        protected void execute(Channel channel) {
            final InputStream in = (InputStream) channel.getExportedObjectOrNull(oid);
            // EOF may be late to the party if we interrupt request, hence we do not fail for this command
            if (in == null) { // Input stream has not been closed yet
                LOGGER.log(Level.FINE, "InputStream with oid=%s has been already unexported", oid);
                return;
            }

            channel.unexport(oid, createdAt, false);
            IOUtils.closeQuietly(in);
        }

        @Override
        public String toString() {
            return "ProxyInputStream.EOF(" + oid + ")";
        }

        private static final long serialVersionUID = 1L;
    }
}
