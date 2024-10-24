/*
 * @(#)$Id: FastPipedOutputStream.java 3619 2008-03-26 07:23:03Z yui $
 *
 * Copyright 2006-2008 Makoto YUI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Makoto YUI - initial implementation
 */
package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PipedOutputStream;
import java.lang.ref.WeakReference;

/**
 * This class is equivalent to {@link PipedOutputStream}. In the
 * interface it only adds a constructor which allows for specifying the buffer
 * size. Its implementation, however, is much simpler and a lot more efficient
 * than its equivalent. It doesn't rely on polling. Instead it uses proper
 * synchronization with its counterpart {@link FastPipedInputStream}.
 *
 * @author WD
 * @see <a href="http://developer.java.sun.com/developer/bugParade/bugs/4404700.html">4404700</a>
 * @see FastPipedOutputStream
 */
public class FastPipedOutputStream extends OutputStream implements ErrorPropagatingOutputStream {

    WeakReference<FastPipedInputStream> sink;

    private final Throwable allocatedAt = new Throwable();

    /**
     * Creates an unconnected PipedOutputStream.
     */
    public FastPipedOutputStream() {
        super();
    }

    /**
     * Creates a PipedOutputStream with a default buffer size and connects it to
     * <code>sink</code>.
     * @exception IOException It was already connected.
     */
    public FastPipedOutputStream(FastPipedInputStream sink) throws IOException {
        connect(sink);
    }

    /**
     * Creates a PipedOutputStream with buffer size <code>bufferSize</code> and
     * connects it to <code>sink</code>.
     * @exception IOException It was already connected.
     * @deprecated as of 1.350
     *      bufferSize parameter is ignored.
     */
    @Deprecated
    public FastPipedOutputStream(FastPipedInputStream sink, int bufferSize) throws IOException {
        this(sink);
    }

    private FastPipedInputStream sink() throws IOException {
        FastPipedInputStream s = sink.get();
        if (s == null) {
            throw new IOException("Reader side has already been abandoned", allocatedAt);
        }
        return s;
    }

    /**
     * @exception IOException The pipe is not connected.
     */
    @Override
    public void close() throws IOException {
        error(null);
    }

    @Override
    public void error(Throwable e) throws IOException {
        if (sink == null) {
            throw new IOException("Unconnected pipe");
        }
        FastPipedInputStream s = sink();
        synchronized (s.buffer) {
            if (s.closed == null) {
                s.closed = new FastPipedInputStream.ClosedBy(e);
                flush();
            }
        }
    }

    /**
     * @exception IOException The pipe is already connected.
     */
    public void connect(FastPipedInputStream sink) throws IOException {
        if (this.sink != null) {
            throw new IOException("Pipe already connected");
        }
        this.sink = new WeakReference<>(sink);
        sink.source = new WeakReference<>(this);
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        close();
    }

    @Override
    public void flush() throws IOException {
        FastPipedInputStream s = sink();
        synchronized (s.buffer) {
            // Release all readers.
            s.buffer.notifyAll();
        }
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b});
    }

    @Override
    public void write(@NonNull byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    /**
     * @exception IOException The pipe is not connected or a reader has closed it.
     */
    @Override
    public void write(@NonNull byte[] b, int off, int len) throws IOException {
        if (sink == null) {
            throw new IOException("Unconnected pipe");
        }

        while (len > 0) {
            FastPipedInputStream s = sink(); // make sure the sink is still trying to read, or else fail the write.

            if (s.closed != null) {
                throw new IOException("Pipe is already closed", s.closed);
            }

            synchronized (s.buffer) {
                if (s.writePosition == s.readPosition && s.writeLaps > s.readLaps) {
                    // The circular buffer is full, so wait for some reader to consume
                    // something.

                    // release a reference to 's' during the wait so that if the reader has abandoned the pipe
                    // we can tell.
                    byte[] buf = s.buffer;

                    Thread t = Thread.currentThread();
                    String oldName = t.getName();
                    t.setName("Blocking to write " + HexDump.toHex(b, off, Math.min(len, 256)) + ": " + oldName);
                    try {
                        buf.wait(TIMEOUT);
                    } catch (InterruptedException e) {
                        throw (InterruptedIOException) new InterruptedIOException(e.getMessage()).initCause(e);
                    } finally {
                        t.setName(oldName);
                    }
                    // Try again.
                    continue;
                }

                // Don't write more than the capacity indicated by len or the space
                // available in the circular buffer.
                int amount = Math.min(
                        len, (s.writePosition < s.readPosition ? s.readPosition : s.buffer.length) - s.writePosition);
                System.arraycopy(b, off, s.buffer, s.writePosition, amount);
                s.writePosition += amount;

                if (s.writePosition == s.buffer.length) {
                    s.writePosition = 0;
                    ++s.writeLaps;
                }

                off += amount;
                len -= amount;

                s.buffer.notifyAll();
            }
        }
    }

    static final int TIMEOUT = Integer.getInteger(FastPipedOutputStream.class.getName() + ".timeout", 10 * 1000);
}
