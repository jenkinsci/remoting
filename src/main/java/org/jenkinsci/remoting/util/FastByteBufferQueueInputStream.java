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

/**
 * An {@link InputStream} backed by a set number of bytes from the head of a {@link ByteBufferQueue}.
 * Assumes that the backing {@link ByteBufferQueue} will not be read by another thread during calls to {@link #read()}
 * so all methods are non-blocking. Does not support {@link #mark(int)}.
 *
 * @since 3.0
 */
public class FastByteBufferQueueInputStream extends InputStream {

    /**
     * The backing queue.
     */
    private final ByteBufferQueue queue;
    /**
     * How much to read off the queue.
     */
    private final int length;
    /**
     * How far we have read.
     */
    private int pos;

    /**
     * Constructs a new instance.
     *
     * @param queue  the backing {@link ByteBufferQueue}.
     * @param length the limit of bytes to take from the backing queue.
     */
    public FastByteBufferQueueInputStream(ByteBufferQueue queue, int length) {
        this.queue = queue;
        this.length = length;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read() throws IOException {
        return pos++ >= length ? -1 : (queue.get() & 0xff);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(@NonNull byte[] b, int off, int len) throws IOException {
        int rem = length - pos;
        if (rem <= 0) {
            return -1;
        }
        int read = queue.get(b, off, Math.min(len, rem));
        if (read <= 0) {
            return -1;
        }
        pos += read;
        return read;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long skip(long n) throws IOException {
        if (pos >= length) {
            return -1;
        }
        if (pos + n >= length) {
            n = length - pos;
        }
        long skipped = queue.skip(n);
        pos += skipped;
        return skipped;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int available() throws IOException {
        return pos >= length ? -1 : length - pos;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void mark(int readlimit) {}

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void reset() throws IOException {}

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean markSupported() {
        return false;
    }
}
