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
import java.io.OutputStream;

/**
 * An {@link OutputStream} backed by a {@link ByteBufferQueue}.
 *
 * @since 3.0
 */
public class ByteBufferQueueOutputStream extends OutputStream {

    /**
     * The backing queue.
     */
    private final ByteBufferQueue queue;

    /**
     * Creates a new instance.
     *
     * @param queue the backing queue.
     */
    public ByteBufferQueueOutputStream(ByteBufferQueue queue) {
        this.queue = queue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(int b) throws IOException {
        queue.put((byte) b);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(@NonNull byte[] b, int off, int len) throws IOException {
        queue.put(b, off, len);
    }
}
