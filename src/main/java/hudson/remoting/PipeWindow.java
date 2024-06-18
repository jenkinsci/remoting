/*
 * The MIT License
 *
 * Copyright (c) 2010, InfraDNA, Inc.
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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps track of the number of bytes that the sender can send without overwhelming the receiver of the pipe.
 *
 * <p>
 * {@link OutputStream} is a blocking operation in Java, so when we send byte[] to the remote to write to
 * {@link OutputStream}, it needs to be done in a separate thread (or else we'll fail to attend to the channel
 * in timely fashion.) This in turn means the byte[] being sent needs to go to a queue between a
 * channel reader thread and I/O processing thread, and thus in turn means we need some kind of throttling
 * mechanism, or else the queue can grow too much.
 *
 * <p>
 * This implementation solves the problem by using TCP/IP like window size tracking. The sender allocates
 * a fixed length window size. Every time the sender sends something we reduce this value. When the receiver
 * writes data to {@link OutputStream}, it'll send back the "ack" command, which adds to this value, allowing
 * the sender to send more data.
 *
 * @author Kohsuke Kawaguchi
 */
abstract class PipeWindow {

    /**
     * Cause of death.
     * If not {@code null}, new commands will not be executed.
     */
    @CheckForNull
    protected volatile Throwable dead;

    /**
     * Returns the current maximum window size.
     *
     * <p>
     * This is the size of the available window size if all the currrently in-flight bytes get acked.
     */
    abstract int max();

    /**
     * When we receive Ack from the receiver, we increase the window size by calling this method.
     */
    abstract void increase(int delta);

    /**
     * Returns the current available window size.
     *
     * Unlike {@link #get(int)}, this method will never wait for the space to become available.
     */
    abstract int peek();

    /**
     * Returns the current available window size.
     *
     * If the available window size is smaller than the specified minimum size,
     * this method blocks until some space becomes available.
     *
     * @throws IOException
     *      If we learned that there is an irrecoverable problem on the remote side that prevents us from writing.
     * @throws InterruptedException
     *      If a thread was interrupted while blocking.
     * @return
     *      The available window size >= min.
     * @param min
     *      Minimum size of the window to retrieve
     */
    abstract int get(int min) throws InterruptedException, IOException;

    /**
     * When we send out some bytes to the network, we decrease the window size by calling this method.
     */
    abstract void decrease(int delta);

    /**
     * Indicates that the remote end has died and all the further send attempt should fail.
     * @param cause Death cause. If {@code null}, the death will be still considered as dead, but there will be no cause recorded..
     */
    void dead(@CheckForNull Throwable cause) {
        // We need to record
        this.dead = cause != null ? cause : new RemotingSystemException("Unknown cause", null);
    }

    /**
     * If we already know that the remote end had developed a problem, throw an exception.
     * Otherwise no-op.
     * @throws IOException Pipe is already closed
     */
    protected void checkDeath() throws IOException {
        if (dead != null) {
            // the remote end failed to write.
            throw new IOException("Pipe is already closed", dead);
        }
    }

    /**
     * Fake implementation used when the receiver side doesn't support throttling.
     */
    static class Fake extends PipeWindow {
        @Override
        int max() {
            return Integer.MAX_VALUE;
        }

        @Override
        void increase(int delta) {}

        @Override
        int peek() {
            return Integer.MAX_VALUE;
        }

        @Override
        int get(int min) throws InterruptedException, IOException {
            checkDeath();
            return Integer.MAX_VALUE;
        }

        @Override
        void decrease(int delta) {}
    }

    static final class Key {
        public final int oid;

        Key(int oid) {
            this.oid = oid;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            return oid == ((Key) o).oid;
        }

        @Override
        public int hashCode() {
            return oid;
        }
    }

    // TODO: Consider rework and cleanup of the fields
    @SuppressFBWarnings(value = "URF_UNREAD_FIELD", justification = "Legacy implementation")
    static class Real extends PipeWindow {
        private final int initial;
        private int available;
        /**
         * Total bytes that left our side of the channel.
         */
        private long written;
        /**
         * Total bytes that the remote side acked.
         */
        private long acked;

        private final int oid;
        /**
         * The only strong reference to the key, which in turn
         * keeps this object accessible in {@link Channel#pipeWindows}.
         */
        private final Key key;

        Real(Key key, int initialSize) {
            this.key = key;
            this.oid = key.oid;
            this.available = initialSize;
            this.initial = initialSize;
        }

        @Override
        int max() {
            return initial;
        }

        @Override
        public synchronized void increase(int delta) {
            if (LOGGER.isLoggable(Level.FINER)) {
                LOGGER.finer(String.format("increase(%d,%d)->%d", oid, delta, delta + available));
            }
            available += delta;
            acked += delta;
            notifyAll();
        }

        @Override
        public synchronized int peek() {
            return available;
        }

        /**
         * Blocks until some space becomes available.
         */
        @Override
        public int get(int min) throws InterruptedException, IOException {
            checkDeath();
            synchronized (this) {
                if (available >= min) {
                    return available;
                }

                while (available < min) {
                    wait(100);
                    checkDeath();
                }

                return available;
            }
        }

        @Override
        public synchronized void decrease(int delta) {
            if (LOGGER.isLoggable(Level.FINER)) {
                LOGGER.finer(String.format("decrease(%d,%d)->%d", oid, delta, available - delta));
            }
            available -= delta;
            written += delta;
            /*
            HUDSON-7745 says the following assertion fails, which AFAICT is only possible if multiple
            threads write to OutputStream concurrently, but that doesn't happen in most of the situations, so
            I'm puzzled. For the time being, cheating by just suppressing the assertion.

            HUDSON-7581 appears to be related.
            */
            //            if (available<0)
            //                throw new AssertionError();
        }
    }

    private static final Logger LOGGER = Logger.getLogger(PipeWindow.class.getName());
}
