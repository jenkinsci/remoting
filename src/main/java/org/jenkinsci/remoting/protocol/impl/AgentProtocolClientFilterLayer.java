/*
 * The MIT License
 *
 * Copyright (c) 2016, Stephen Connolly, CloudBees, Inc.
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
import net.jcip.annotations.GuardedBy;
import org.jenkinsci.remoting.protocol.FilterLayer;
import org.jenkinsci.remoting.util.ByteBufferQueue;
import org.jenkinsci.remoting.util.ByteBufferUtils;

/**
 * A {@link FilterLayer} that sends the AgentProtocol client handshake.
 *
 * @since 3.0
 */
public class AgentProtocolClientFilterLayer extends FilterLayer {
    /**
     * The read-only send buffer.
     */
    private final ByteBuffer sendProtocol;
    /**
     * The queue of messages to send once the acknowledgement has been completed.
     */
    @GuardedBy("sendLock")
    private final ByteBufferQueue sendQueue = new ByteBufferQueue(8192);

    /**
     * Constructor
     *
     * @param name the {@code AgentProtocol.getName()}.
     */
    public AgentProtocolClientFilterLayer(String name) {
        this.sendProtocol = ByteBufferUtils.wrapUTF8("Protocol:" + name).asReadOnlyBuffer();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws IOException {
        try {
            doSend(EMPTY_BUFFER);
        } catch (ConnectionRefusalException e) {
            // ignore
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRecv(@NonNull ByteBuffer data) throws IOException {
        next().onRecv(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void doSend(@NonNull ByteBuffer data) throws IOException {
        if (sendProtocol.hasRemaining()) {
            sendQueue.put(data);
            next().doSend(sendProtocol);
            return;
        }
        if (next() != null) {
            if (sendQueue.hasRemaining()) {
                sendQueue.put(data);
                flushSend(sendQueue);
            } else {
                next().doSend(data);
            }
            completed();
        }
    }
}
