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
package org.jenkinsci.remoting.protocol.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.jenkinsci.remoting.protocol.FilterLayer;
import org.jenkinsci.remoting.util.ByteBufferUtils;

public class BatchSendBufferingFilterLayer extends FilterLayer implements Cloneable {

    private final ByteBuffer batch;
    private final Object lock = new Object();

    public BatchSendBufferingFilterLayer(int length) {
        this.batch = ByteBuffer.allocate(length);
    }

    @Override
    public BatchSendBufferingFilterLayer clone() {
        return new BatchSendBufferingFilterLayer(batch.capacity());
    }

    @Override
    public String toString() {
        return "BatchSendBufferingFilterLayer{" + "length=" + batch.capacity() + '}';
    }

    @Override
    public void onRecv(@NonNull ByteBuffer data) throws IOException {
        next().onRecv(data);
    }

    @Override
    public void doSend(@NonNull ByteBuffer data) throws IOException {
        synchronized (lock) {
            while (data.hasRemaining()) {
                if (batch.hasRemaining()) {
                    ByteBufferUtils.put(data, batch);
                }
                if (!batch.hasRemaining()) {
                    batch.flip();
                    while (batch.hasRemaining()) {
                        next().doSend(batch);
                    }
                    batch.clear();
                }
            }
        }
    }

    public void flush() throws IOException {
        synchronized (lock) {
            if (batch.position() > 0) {
                batch.flip();
                while (batch.hasRemaining()) {
                    next().doSend(batch);
                }
                batch.clear();
            }
        }
    }
}
