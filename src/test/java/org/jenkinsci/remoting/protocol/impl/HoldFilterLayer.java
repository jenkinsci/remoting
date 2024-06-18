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
import org.jenkinsci.remoting.util.ByteBufferQueue;

public class HoldFilterLayer extends FilterLayer {

    private final ByteBufferQueue recvQueue = new ByteBufferQueue(8192);
    private final ByteBufferQueue sendQueue = new ByteBufferQueue(8192);
    private final Object holdLock = new Object();
    private boolean holding = true;

    public void release() throws IOException {
        synchronized (holdLock) {
            if (!holding) {
                return;
            }
            holding = false;
            onRecv(EMPTY_BUFFER);
            doSend(EMPTY_BUFFER);
        }
    }

    @Override
    public void onRecv(@NonNull ByteBuffer data) throws IOException {
        synchronized (holdLock) {
            if (holding) {
                synchronized (recvQueue) {
                    recvQueue.put(data);
                }
                return;
            }
        }
        synchronized (recvQueue) {
            if (recvQueue.hasRemaining()) {
                recvQueue.put(data);
                ByteBuffer tempBuffer = recvQueue.newByteBuffer();
                while (recvQueue.hasRemaining()) {
                    tempBuffer.clear();
                    recvQueue.get(tempBuffer);
                    next().onRecv(tempBuffer);
                }
            } else if (data.hasRemaining()) {
                next().onRecv(data);
            }
        }
    }

    @Override
    public void doSend(@NonNull ByteBuffer data) throws IOException {
        synchronized (holdLock) {
            if (holding) {
                synchronized (sendQueue) {
                    sendQueue.put(data);
                }
                return;
            }
        }
        synchronized (sendQueue) {
            if (sendQueue.hasRemaining()) {
                sendQueue.put(data);
                ByteBuffer tempBuffer = sendQueue.newByteBuffer();
                while (sendQueue.hasRemaining()) {
                    tempBuffer.clear();
                    sendQueue.get(tempBuffer);
                    next().doSend(tempBuffer);
                }
            } else if (data.hasRemaining()) {
                next().doSend(data);
            }
        }
    }
}
