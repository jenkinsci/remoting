/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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
package org.jenkinsci.remoting.util;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * An {@link InputStream} backed by a {@link ByteBufferQueue}. Assumes that the backing {@link ByteBufferQueue} will
 * not be updated by another thread during calls to {@link #read()} so all methods are non-blocking and an empty
 * {@link ByteBufferQueue} is deemed equivalent to end of stream.
 *
 * @since 3.0
 */
public class ByteBufferQueueInputStream extends InputStream {

    /**
     * The backing queue.
     */
    private final ByteBufferQueue queue;

    private final int length;
    private int pos;
    /**
     * Any mark if a mark has been defined.
     */
    private ByteBuffer mark = null;

    /**
     * Constructs a new instance.
     *
     * @param queue the backing {@link ByteBufferQueue}.
     */
    public ByteBufferQueueInputStream(ByteBufferQueue queue) {
        this.queue = queue;
        this.length = -1;
    }

    /**
     * Constructs a new instance.
     *
     * @param queue  the backing {@link ByteBufferQueue}.
     * @param length the limit of bytes to take from the backing queue.
     */
    public ByteBufferQueueInputStream(ByteBufferQueue queue, int length) {
        this.queue = queue;
        this.length = length;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read() throws IOException {
        if (length != -1) {
            // we can assume that there is at least length bytes in the queue from the constructor.
            if (pos >= length) {
                return -1;
            } else if (mark != null) {
                if (mark.hasRemaining()) {
                    pos++;
                    byte b = queue.get();
                    mark.put(b);
                    return b & 0xff;
                } else {
                    mark = null;
                }
            }
            pos++;
            return queue.get() & 0xff;
        } else {
            try {
                byte b = queue.get();
                pos++;
                if (mark != null) {
                    if (mark.hasRemaining()) {
                        mark.put(b);
                    } else {
                        // mark was invalidated as there was more data than reserved
                        mark = null;
                    }
                }
                return b & 0xff;
            } catch (BufferUnderflowException e) {
                return -1;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(@NonNull byte[] b, int off, int len) throws IOException {
        if (length != -1) {
            int rem = length - pos;
            if (rem <= 0) {
                return -1;
            } else if (len > rem) {
                len = rem;
            }
        }
        int read = queue.get(b, off, len);
        if (read <= 0) {
            return -1;
        }
        pos += read;
        if (mark != null) {
            if (mark.remaining() > read) {
                mark.put(b, off, read);
            } else {
                // mark was invalidated as there was more data than reserved
                mark = null;
            }
        }
        return read;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long skip(long n) throws IOException {
        if (mark == null || mark.remaining() < n) {
            mark = null;
            if (length != -1) {
                if (pos >= length) {
                    return -1;
                }
                if (pos + n >= length) {
                    n = length - pos;
                }
            }
            long skipped = queue.skip(n);
            pos += skipped;
            return skipped;
        }
        int l = mark.limit();
        int p = mark.position();
        ((Buffer) mark).limit(mark.position() + (int) n);
        queue.get(mark);
        int skipped = mark.position() - p;
        ((Buffer) mark).limit(l);
        return skipped;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int available() throws IOException {
        if (length == -1) {
            long remaining = queue.remaining();
            return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
        } else {
            return pos >= length ? -1 : length - pos;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void mark(int readlimit) {
        if (mark != null) {
            if (mark.capacity() <= readlimit) {
                ((Buffer) mark).clear();
                ((Buffer) mark).limit(readlimit);
                return;
            }
        }
        mark = ByteBuffer.allocate(readlimit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void reset() throws IOException {
        if (mark == null) {
            throw new IOException();
        }
        ((Buffer) mark).flip();
        pos -= mark.remaining();
        queue.unget(mark);
        ((Buffer) mark).clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean markSupported() {
        return true;
    }
}
