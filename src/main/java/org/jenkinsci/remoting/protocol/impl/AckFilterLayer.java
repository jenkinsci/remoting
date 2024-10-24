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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.jcip.annotations.GuardedBy;
import org.jenkinsci.remoting.protocol.FilterLayer;
import org.jenkinsci.remoting.util.ByteBufferQueue;
import org.jenkinsci.remoting.util.ByteBufferUtils;

/**
 * A {@link FilterLayer} that ensures both sides will not proceed unless the acknowledgement has been sent and
 * received by both sides.
 *
 * @since 3.0
 */
public class AckFilterLayer extends FilterLayer {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(AckFilterLayer.class.getName());
    /**
     * Lock for receiving messages.
     */
    private final Object recvLock = new Object();
    /**
     * Lock for sending messages.
     */
    private final Object sendLock = new Object();
    /**
     * The read-only send buffer.
     */
    private final ByteBuffer sendAck;
    /**
     * Buffer to hold the received acknowledgement.
     */
    private final ByteBuffer recvAck;
    /**
     * The queue of messages to send once the acknowledgement has been completed.
     */
    @GuardedBy("sendLock")
    private final ByteBufferQueue sendQueue = new ByteBufferQueue(8192);
    /**
     * The queue of messages to receive once the acknowledgement has been completed.
     */
    @GuardedBy("recvLock")
    private final ByteBufferQueue recvQueue = new ByteBufferQueue(8192);
    /**
     * Write once field to optimize calls to {@link #receivedAck()}.
     */
    private boolean receivedAck;
    /**
     * Flag to indicate that the acknowledgement has been aborted.
     */
    private volatile boolean aborted;
    /**
     * A timeout for receiving the acknowledgement from the remote end.
     */
    @GuardedBy("sendLock")
    private Future<?> timeout;

    /**
     * Default constructor.
     */
    public AckFilterLayer() {
        this("ACK");
    }

    /**
     * Constructor using a custom acknowledgement string.
     *
     * @param ack the acknowledgement string.
     */
    public AckFilterLayer(String ack) {
        this.sendAck = ByteBufferUtils.wrapUTF8(ack).asReadOnlyBuffer();
        this.recvAck = ByteBuffer.allocate(sendAck.capacity());
    }

    private static String toHexString(ByteBuffer buffer) {
        ByteBuffer expectAck = buffer.duplicate();
        ((Buffer) expectAck).position(0);
        ((Buffer) expectAck).limit(buffer.position());
        StringBuilder expectHex = new StringBuilder(expectAck.remaining() * 2);
        while (expectAck.hasRemaining()) {
            int b = expectAck.get() & 0xff;
            if (b < 16) {
                expectHex.append('0');
            }
            expectHex.append(Integer.toHexString(b));
        }
        return expectHex.toString();
    }

    @SuppressFBWarnings(
            value = "FORMAT_STRING_MANIPULATION",
            justification = "As this converts a String to a Hex string there is little that can be manipulated.")
    private void abort(String type) throws ConnectionRefusalException {
        aborted = true;
        if (LOGGER.isLoggable(Level.WARNING)) {
            LOGGER.log(Level.WARNING, "[{0}] {1} acknowledgement sequence, expected 0x{2} got 0x{3}", new Object[] {
                stack().name(), type, toHexString(sendAck), toHexString(recvAck)
            });
        }
        ConnectionRefusalException cause = new ConnectionRefusalException(String.format(
                type + " acknowledgement received, expected 0x%s got 0x%s",
                toHexString(sendAck),
                toHexString(recvAck)));
        abort(cause);
        throw cause;
    }

    private boolean receivedAck() {
        if (receivedAck) {
            return true;
        }
        ByteBuffer expectAck = sendAck.duplicate();
        ByteBuffer actualAck = recvAck.duplicate();
        ((Buffer) expectAck).rewind();
        ((Buffer) actualAck).rewind();
        receivedAck = expectAck.equals(actualAck);
        return receivedAck;
    }

    private boolean receivedPartialAck() {
        if (receivedAck) {
            return true;
        }
        ByteBuffer expectAck = sendAck.duplicate();
        ByteBuffer actualAck = recvAck.duplicate();
        ((Buffer) expectAck).position(0);
        ((Buffer) expectAck).limit(sendAck.position());
        ((Buffer) actualAck).position(0);
        ((Buffer) actualAck).limit(recvAck.position());
        while (expectAck.hasRemaining() && actualAck.hasRemaining()) {
            byte e = expectAck.get();
            byte a = actualAck.get();
            if (e != a) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws IOException {
        synchronized (sendLock) {
            timeout = stack().executeLater(
                            () -> {
                                LOGGER.info("Timeout waiting for ACK");
                                IOException cause = new IOException("Timeout waiting for ACK");
                                abort(cause);
                                try {
                                    doCloseSend();
                                    onRecvClosed(cause);
                                } catch (IOException e) {
                                    // ignore
                                }
                            },
                            stack().getHandshakingTimeout(),
                            stack().getHandshakingUnits());
        }
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
        if (aborted) {
            // if aborted then the buffers are immutable, so no lock needed
            if (!sendAck.hasRemaining()) {
                throw new ConnectionRefusalException(String.format(
                        "Incorrect acknowledgement received, expected 0x%s got 0x%s",
                        toHexString(sendAck), toHexString(recvAck)));
            }
            throw new ConnectionRefusalException("Connection closed before acknowledgement send");
        }
        synchronized (recvLock) {
            if (recvAck.hasRemaining()) {
                ByteBufferUtils.put(data, recvAck);
                if (recvAck.hasRemaining()) {
                    if (!receivedPartialAck()) {
                        abort("Incorrect");
                    } else {
                        if (LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.log(Level.FINEST, "[{0}] Expecting {1} more bytes of acknowledgement", new Object[] {
                                stack().name(), recvAck.remaining()
                            });
                        }
                    }
                    return;
                }
            }
        }
        if (receivedAck()) {
            try {
                synchronized (sendLock) {
                    if (timeout != null) {
                        timeout.cancel(false);
                        timeout = null;
                    }
                    if (sendQueue.hasRemaining()) {
                        flushSend(sendQueue);
                    }
                }
            } catch (IOException e) {
                synchronized (recvLock) {
                    recvQueue.put(data);
                }
                throw e;
            }
            boolean recvQueueHadRemaining;
            synchronized (recvLock) {
                recvQueueHadRemaining = recvQueue.hasRemaining();
                if (recvQueueHadRemaining) {
                    recvQueue.put(data);
                    flushRecv(recvQueue);
                }
            }
            if (recvQueueHadRemaining) {
                synchronized (sendLock) {
                    if (!sendQueue.hasRemaining()) {
                        complete();
                    }
                }
            } else if (data.hasRemaining()) {
                next().onRecv(data);
            }
        } else {
            abort("Incorrect");
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRecvClosed(IOException cause) throws IOException {
        synchronized (recvLock) {
            if (recvAck.hasRemaining() && recvAck.position() > 0) {
                super.onRecvClosed(new ConnectionRefusalException(
                        cause,
                        "Partial acknowledgement received, expecting 0x%s got 0x%s",
                        toHexString(sendAck),
                        toHexString(recvAck)));
                return;
            }
        }
        IOException rootCause;
        synchronized (sendLock) {
            if (sendAck.hasRemaining()) {
                rootCause = cause;
            } else {
                rootCause = new ConnectionRefusalException("Connection closed before acknowledgement sent");
            }
        }
        synchronized (recvLock) {
            super.onRecvClosed(rootCause);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRecvOpen() {
        return super.isRecvOpen() && !aborted;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void doSend(@NonNull ByteBuffer data) throws IOException {
        if (aborted) {
            if (!sendAck.hasRemaining()) {
                throw new ConnectionRefusalException(String.format(
                        "Incorrect acknowledgement received, expected 0x%s got 0x%s",
                        toHexString(sendAck), toHexString(recvAck)));
            }
            throw new ConnectionRefusalException("Connection closed before acknowledgement send");
        }
        synchronized (sendLock) {
            if (sendAck.hasRemaining()) {
                sendQueue.put(data);
                next().doSend(sendAck);
                return;
            }
        }
        synchronized (recvLock) {
            if (recvAck.hasRemaining()) {
                sendQueue.put(data);
                return;
            }
        }
        if (receivedAck()) {
            synchronized (sendLock) {
                if (timeout != null) {
                    timeout.cancel(false);
                    timeout = null;
                }
                if (sendQueue.hasRemaining()) {
                    sendQueue.put(data);
                    flushSend(sendQueue);
                } else {
                    try {
                        next().doSend(data);
                    } catch (IOException e) {
                        sendQueue.put(data);
                        throw e;
                    }
                }
            }
            synchronized (recvLock) {
                if (recvQueue.hasRemaining()) {
                    flushRecv(recvQueue);
                }
            }
            complete();
        } else {
            abort("Incorrect");
        }
    }

    private void complete() {
        if (LOGGER.isLoggable(Level.FINE)) {
            String name = stack().name();
            completed();
            LOGGER.log(Level.FINE, "[{0}] Acknowledgement exchange completed", name);
        } else {
            completed();
        }
    }
}
