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

import java.nio.Buffer;
import java.nio.ByteBuffer;
import net.jcip.annotations.GuardedBy;

/**
 * A buffer pool that keeps a free list of {@link ByteBuffer}s of a specified default size in a simple fixed
 * size stack.
 *
 * If the stack is full, the buffer is de-referenced and available to be freed by normal garbage collection (whenever
 * that may actually take place)
 *
 * @see <a href="http://bugs.java.com/view_bug.do?bug_id=4469299">JDK-4469299</a>
 * @see <a href="http://www.evanjones.ca/java-bytebuffer-leak.html">More detail</a>
 */
public class DirectByteBufferPool implements ByteBufferPool {
    /**
     * The pool of {@link ByteBuffer}. Only the first {@link #poolCount} elements will contain buffers.
     */
    @GuardedBy("this")
    private final ByteBuffer[] pool;
    /**
     * The minimum size to allocate buffers.
     */
    private final int bufferSize;
    /**
     * The number of buffers currently held in the pool.
     */
    @GuardedBy("this")
    private int poolCount;

    /**
     * Constructor.
     * @param minBufferSize the minimum size to create buffers.
     * @param maxPoolSize the maximum buffers to keep in the pool.
     */
    public DirectByteBufferPool(int minBufferSize, int maxPoolSize) {
        this.pool = new ByteBuffer[maxPoolSize];
        this.bufferSize = minBufferSize;
        this.poolCount = 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ByteBuffer acquire(int size) {
        synchronized (this) {
            if (poolCount > 0) {
                if (size <= bufferSize) {
                    // anyone will do
                    poolCount--;
                    ByteBuffer result = pool[poolCount];
                    pool[poolCount] = null; // drop reference
                    ((Buffer) result).limit(size);
                    return result;
                }
                for (int i = 0; i < poolCount; i++) {
                    if (pool[i].capacity() >= size) {
                        ByteBuffer result = pool[i];
                        poolCount--;
                        pool[i] = pool[poolCount]; // swap the big for a small
                        pool[poolCount] = null; // drop reference
                        ((Buffer) result).limit(size);
                        return result;
                    }
                }
            }
        }
        ByteBuffer result = ByteBuffer.allocateDirect(Math.max(bufferSize, size));
        ((Buffer) result).limit(size);
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void release(ByteBuffer buffer) {
        if (buffer.isDirect() && !buffer.isReadOnly() && buffer.capacity() >= bufferSize) {
            synchronized (this) {
                // we will let GC tidy any that are smaller than our size
                if (poolCount < pool.length) {
                    ((Buffer) buffer).clear();
                    pool[poolCount++] = buffer;
                }
            }
        }
    }
}
