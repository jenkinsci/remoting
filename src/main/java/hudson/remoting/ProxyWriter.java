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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.jcip.annotations.GuardedBy;

/**
 * {@link Writer} that sends bits to an exported
 * {@link Writer} on a remote machine.
 */
final class ProxyWriter extends Writer {

    @GuardedBy("this")
    private Channel channel;

    private int oid;

    private PipeWindow window;

    /**
     * If bytes are written to this stream before it's connected
     * to a remote object, bytes will be stored in this buffer.
     */
    private CharArrayWriter tmp;

    /**
     * Keeps the close request cause.
     * If the field is not {@code null}, it means that the writer is being closed and hence not writable anymore.
     */
    @CheckForNull
    private Throwable closeCause;
    /**
     * Indicates that the object does not longer hold the channel instance.
     * This is asynchronous toggle flag.
     */
    private volatile boolean channelReleased;

    /**
     * Creates an already connected {@link ProxyWriter}.
     *
     * @param oid
     *      The object id of the exported {@link Writer}.
     */
    public ProxyWriter(@NonNull Channel channel, int oid) throws IOException {
        connect(channel, oid);
    }

    /**
     * Connects this stream to the specified remote object.
     */
    synchronized void connect(@NonNull Channel channel, int oid) throws IOException {
        if (this.channel != null) {
            throw new IllegalStateException("Cannot connect twice");
        }
        if (oid == 0) {
            throw new IllegalArgumentException("oid=0");
        }
        this.channel = channel;
        this.oid = oid;

        window = channel.getPipeWindow(oid);

        // if we already have bytes to write, do so now.
        if (tmp != null) {
            char[] b = tmp.toCharArray();
            tmp = null;
            _write(b, 0, b.length);
        }
        if (closeCause != null) { // already closed asynchronously?
            close();
        }
    }

    @Override
    public void write(int c) throws IOException {
        write(new char[] {(char) c}, 0, 1);
    }

    @Override
    public void write(@NonNull char[] cbuf, int off, int len) throws IOException {
        if (closeCause != null) {
            throw new IOException("stream is already closed");
        }
        _write(cbuf, off, len);
    }

    /**
     * {@link #write(char[])} without the close check.
     */
    private synchronized void _write(char[] cbuf, int off, int len) throws IOException {
        if (channel == null) {
            if (tmp == null) {
                tmp = new CharArrayWriter();
            }
            tmp.write(cbuf);
        } else {
            final int max = window.max();

            while (len > 0) {
                int sendable;
                try {
                    /*
                       To avoid fragmentation of the pipe window, at least demand that 10% of the pipe window
                       be reclaimed.

                       Imagine a large latency network where we are always low on the window size,
                       and we are continuously sending data of irregular size. In such a circumstance,
                       a fragmentation will happen. We start sending out a small Chunk at a time (say 4 bytes),
                       and when its Ack comes back, it gets immediately consumed by another out-bound Chunk of 4 bytes.

                       Clearly, it's better to wait a bit until we have a sizable pipe window, then send out
                       a bigger Chunk, since Chunks have static overheads. This code does just that.

                       (Except when what we are trying to send as a whole is smaller than the current available
                       window size, in which case there's no point in waiting.)
                    */
                    sendable = Math.min(window.get(Math.min(max / 10, len)), len);
                    /*
                       Imagine if we have a lot of data to send and the pipe window is fully available.
                       If we create one Chunk that fully uses the window size, we need to wait for the
                       whole Chunk to get to the other side, then the Ack to come back to this side,
                       before we can send a next Chunk. While the Ack is traveling back to us, we have
                       to sit idle. This fails to utilize available bandwidth.

                       A better strategy is to create a smaller Chunk, say half the window size.
                       This allows the other side to send back the ack while we are sending the second
                       Chunk. In a network with a non-trivial latency, this allows Chunk and Ack
                       to overlap, and that improves the utilization.

                       It's not clear what the best size of the chunk to send (there's a certain
                       overhead in our Command structure, around 100-200 bytes), so I'm just starting
                       with 2. Further analysis would be needed to determine the best value.
                    */
                    sendable = Math.min(sendable, max / 2);
                } catch (InterruptedException e) {
                    throw (IOException) new InterruptedIOException().initCause(e);
                }

                channel.send(new Chunk(channel.newIoId(), oid, cbuf, off, sendable));
                window.decrease(sendable);
                off += sendable;
                len -= sendable;
            }
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        if (channel != null && channel.remoteCapability.supportsProxyWriter2_35()) {
            channel.send(new Flush(channel.newIoId(), oid));
        }
    }

    @Override
    public synchronized void close() throws IOException {
        error(null);
    }

    /**
     * Gets the close cause.
     *
     * @return Close cause. {@code null} if the writer close has not been requested yet.
     *         Nonnull values indicate that the writer may be still active, but it won't accept new write commands in such case.
     * @since 3.15
     */
    @CheckForNull
    public Throwable getCloseCause() {
        return closeCause;
    }

    /**
     * Reports error and immediately terminates the writer.
     * @param cause Cause
     * @throws IOException if failed to send the {@link EOF} command to the remote side.
     *                     The writer will be considered as closed even in such case.
     */
    public void error(@CheckForNull Throwable cause) throws IOException {
        Throwable terminationCause = cause != null ? cause : new IOException("ProxyWriter close has been requested");
        if (channelReleased) {
            // Channel is already closed, do nothing
            if (LOGGER.isLoggable(Level.FINE)) {
                final IOException ex;
                if (closeCause != null) {
                    ex = new IOException("Writer is already closed", closeCause);
                    ex.addSuppressed(terminationCause);
                } else {
                    ex = new IOException("Writer is already closed", terminationCause);
                }
                LOGGER.log(Level.FINE, "Trying to close the already closed writer", ex);
            }
            return;
        }

        if (closeCause == null) {
            // There is a slight risk of race condition here, but we do not really care.
            // If two termination events come at the same time, we will just cache a random one.
            this.closeCause = terminationCause;
        }

        synchronized (this) {
            // TODO: Bug. If the channel cannot send the command, the channel object will be never released and garbage
            // collected
            if (channel != null) {
                // Close the writer on the remote side. This call may be invoked multiple times until the channel is
                // released
                // TODO: send cause over the channel
                channel.send(new EOF(channel.newIoId(), oid /*,error*/));
                channel = null;
                channelReleased = true;
                oid = -1;
            }
        }
    }

    @Override
    // TODO: really?
    @SuppressFBWarnings(value = "FI_FINALIZER_NULLS_FIELDS", justification = "As designed")
    protected synchronized void finalize() throws Throwable {
        super.finalize();
        // if we haven't done so, release the exported object on the remote side.
        // if the object is auto-unexported, the export entry could have already been removed.
        if (channel != null) {
            if (channel.remoteCapability.supportsProxyWriter2_35()) {
                channel.send(new Unexport(channel.newIoId(), oid));
            } else {
                channel.send(new EOF(channel.newIoId(), oid));
            }
            channel = null;
            oid = -1;
        }
    }

    /**
     * {@link Command} for sending bytes.
     */
    private static final class Chunk extends Command {
        private final int ioId;
        private final int oid;
        private final char[] buf;

        public Chunk(int ioId, int oid, char[] buf, int start, int len) {
            // to improve the performance when a channel is used purely as a pipe,
            // don't record the stack trace. On FilePath.writeToTar case, the stack trace and the OOS header
            // takes up about 1.5K.
            super(false);
            this.ioId = ioId;
            this.oid = oid;
            if (start == 0 && len == buf.length) {
                this.buf = buf;
            } else {
                this.buf = new char[len];
                System.arraycopy(buf, start, this.buf, 0, len);
            }
        }

        @Override
        protected void execute(final Channel channel) throws ExecutionException {
            final Writer os = (Writer) channel.getExportedObject(oid);
            channel.pipeWriter.submit(ioId, () -> {
                try {
                    os.write(buf);
                } catch (IOException e) {
                    try {
                        if (channel.remoteCapability.supportsProxyWriter2_35()) {
                            channel.send(new NotifyDeadWriter(channel, e, oid));
                        }
                    } catch (ChannelClosedException x) {
                        // the other direction can be already closed if the connection
                        // shut down is initiated from this side. In that case, remain silent.
                    } catch (IOException x) {
                        // ignore errors
                        LOGGER.log(Level.WARNING, "Failed to notify the sender that the write end is dead", x);
                        LOGGER.log(Level.WARNING, "... the failed write was:", e);
                    }
                } finally {
                    if (channel.remoteCapability.supportsProxyWriter2_35()) {
                        try {
                            channel.send(new Ack(oid, buf.length));
                        } catch (ChannelClosedException x) {
                            // the other direction can be already closed if the connection
                            // shut down is initiated from this side. In that case, remain silent.
                        } catch (IOException e) {
                            // ignore errors
                            LOGGER.log(Level.WARNING, "Failed to ack the stream", e);
                        }
                    }
                }
            });
        }

        @Override
        public String toString() {
            return "ProxyWriter.Chunk(" + oid + "," + buf.length + ")";
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link Command} for flushing.
     * @since 2.35
     */
    private static final class Flush extends Command {
        private final int oid;
        private final int ioId;

        public Flush(int ioId, int oid) {
            super(false);
            this.ioId = ioId;
            this.oid = oid;
        }

        @Override
        protected void execute(Channel channel) throws ExecutionException {
            final Writer os = (Writer) channel.getExportedObject(oid);
            channel.pipeWriter.submit(ioId, () -> {
                try {
                    os.flush();
                } catch (IOException e) {
                    // ignore errors
                }
            });
        }

        @Override
        public String toString() {
            return "ProxyWriter.Flush(" + oid + ")";
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link Command} for releasing an export table.
     *
     * <p>
     * Unlike {@link EOF}, this just unexports but not closes the stream.
     * @since 2.35
     */
    private static class Unexport extends Command {
        private final int oid;
        private final int ioId;

        public Unexport(int ioId, int oid) {
            this.ioId = ioId;
            this.oid = oid;
        }

        @Override
        protected void execute(final Channel channel) {
            channel.pipeWriter.submit(ioId, () -> channel.unexport(oid, createdAt, false));
        }

        @Override
        public String toString() {
            return "ProxyWriter.Unexport(" + oid + ")";
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link Command} for sending EOF.
     */
    private static final class EOF extends Command {
        private final int oid;
        private final int ioId;

        public EOF(int ioId, int oid) {
            this.ioId = ioId;
            this.oid = oid;
        }

        @Override
        protected void execute(final Channel channel) {
            final Writer os = (Writer) channel.getExportedObjectOrNull(oid);
            // EOF may be late to the party if we interrupt request, hence we do not fail for this command
            if (os == null) { // Input stream has not been closed yet
                LOGGER.log(Level.FINE, "ProxyWriter with oid=%s has been already unexported", oid);
                return;
            }
            channel.pipeWriter.submit(ioId, () -> {
                channel.unexport(oid, createdAt, false);
                try {
                    os.close();
                } catch (IOException e) {
                    // ignore errors
                }
            });
        }

        @Override
        public String toString() {
            return "ProxyWriter.EOF(" + oid + ")";
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link Command} to notify the sender that it can send some more data.
     * @since 2.35
     */
    private static class Ack extends Command {
        /**
         * The oid of the {@link Writer} on the receiver side of the data.
         */
        private final int oid;
        /**
         * The number of bytes that were freed up.
         */
        private final int size;

        private Ack(int oid, int size) {
            super(false); // performance optimization
            this.oid = oid;
            this.size = size;
        }

        @Override
        protected void execute(Channel channel) {
            PipeWindow w = channel.getPipeWindow(oid);
            w.increase(size);
        }

        @Override
        public String toString() {
            return "ProxyWriter.Ack(" + oid + ',' + size + ")";
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * {@link Command} to notify the sender that the receiver is dead.
     * @since 2.35
     */
    private static final class NotifyDeadWriter extends Command {
        private final int oid;

        private NotifyDeadWriter(Channel channel, Throwable cause, int oid) {
            super(channel, cause);
            this.oid = oid;
        }

        @Override
        protected void execute(Channel channel) {
            PipeWindow w = channel.getPipeWindow(oid);
            w.dead(createdAt != null ? createdAt.getCause() : null);
        }

        @Override
        public String toString() {
            return "ProxyWriter.Dead(" + oid + ")";
        }

        private static final long serialVersionUID = 1L;
    }

    private static final Logger LOGGER = Logger.getLogger(ProxyWriter.class.getName());
}
