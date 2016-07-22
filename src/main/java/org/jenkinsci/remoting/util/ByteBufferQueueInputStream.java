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

import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * An {@link InputStream} backed by a {@link ByteBufferQueue}. Assumes that the backing {@link ByteBufferQueue} will
 * not be updated by another thread during calls to {@link #read()} so all methods are non-blocking and an empty
 * {@link ByteBufferQueue} is deemed equivalent to end of stream.
 *
 * @since FIXME
 */
public class ByteBufferQueueInputStream extends InputStream {

    /**
     * The backing queue.
     */
    private final ByteBufferQueue queue;
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
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read() throws IOException {
        try {
            byte b = queue.get();
            if (mark != null) {
                if (mark.hasRemaining()) {
                    mark.put(b);
                } else {
                    // mark was invalidated as there was more data than reserved
                    mark = null;
                }
            }
            return b;
        } catch (BufferUnderflowException e) {
            return -1;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(byte[] b) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(b);
        queue.get(buffer);
        if (mark != null) {
            if (mark.remaining() > buffer.position()) {
                buffer.flip();
                mark.put(buffer);
            } else {
                // mark was invalidated as there was more data than reserved
                mark = null;
            }
        }
        return buffer.position();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
        queue.get(buffer);
        if (mark != null) {
            if (mark.remaining() > buffer.position()) {
                buffer.flip();
                mark.put(buffer);
            } else {
                // mark was invalidated as there was more data than reserved
                mark = null;
            }
        }
        return buffer.position();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long skip(long n) throws IOException {
        return queue.skip(n);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int available() throws IOException {
        long remaining = queue.remaining();
        return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void mark(int readlimit) {
        if (mark != null) {
            if (mark.capacity() <= readlimit) {
                mark.clear();
                mark.limit(readlimit);
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
        mark.flip();
        queue.unget(mark);
        mark.position(0);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean markSupported() {
        return true;
    }
}
