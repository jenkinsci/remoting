/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc., Stephen Connolly
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
package org.jenkinsci.remoting.protocol;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.OverrideMustInvoke;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jenkinsci.remoting.util.ByteBufferQueue;

/**
 * The lowest {@link ProtocolLayer} in a {@link ProtocolStack}. This layer is responsible for sending the output of
 * the protocol
 * to the recipient and injecting the input from the recipient into the protocol stack.
 *
 * @since 3.0
 */
public abstract class NetworkLayer implements ProtocolLayer, ProtocolLayer.Send {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(NetworkLayer.class.getName());
    /**
     * The standard capacity for {@link ByteBuffer}s.
     */
    private static final int CAPACITY = 8192;
    /**
     * Our {@link IOHub}.
     */
    @NonNull
    private final IOHub ioHub;

    /**
     * The send queue of any data requested to send before a call to {@link #start()}.
     * In theory this should not be needed as the network layer will be the first layer to be
     * {@link ProtocolLayer#start()}
     * but it can simplify the other layer implementations if they can queue up data to output in their call to
     * {@link ProtocolLayer#init(ProtocolStack.Ptr)} which is what this queue facilitates.
     */
    private ByteBufferQueue sendQueue;
    /**
     * The receive queue of any data received before a call to {@link #start()}.
     * Depending on how the network layer is implemented, we may start receiving data any time after
     * {@link #NetworkLayer(IOHub)} but we are not allowed to deliver it until after {@link #start()}.
     */
    private ByteBufferQueue recvQueue;

    /**
     * Our {@link ProtocolStack.Ptr}.
     */
    private ProtocolStack<?>.Ptr ptr;

    /**
     * Constructor.
     *
     * @param ioHub the {@link IOHub} that we use.
     */
    public NetworkLayer(@NonNull IOHub ioHub) {
        this.ioHub = ioHub;
        this.recvQueue = new ByteBufferQueue(CAPACITY);
        this.sendQueue = new ByteBufferQueue(CAPACITY);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void doSend(@NonNull ByteBuffer data) throws IOException {
        ByteBufferQueue sendQueue = this.sendQueue;
        if (ptr == null) {
            sendQueue.put(data);
        } else {
            if (sendQueue != null && sendQueue.hasRemaining()) {
                sendQueue.put(data);
                flushSendQueue();
            } else {
                write(data);
            }
        }
    }

    /**
     * SPI: Perform the actual write to the recipient. This method should be non-blocking. The data should be enqueued
     * and written in the order of calls to write()}.
     *
     * @param data the data received. Any data consumed from the {@link ByteBuffer} can be assumed as processed.
     *             Any data not consumed from the {@link ByteBuffer} will be the responsibility of the caller
     *             to resubmit in subsequent calls.
     * @throws IOException if something goes wrong
     */
    protected abstract void write(@NonNull ByteBuffer data) throws IOException;

    /**
     * SPI: Performed the handling of te actual read from the recipient.
     *
     * @param data the data received. Any data consumed from the {@link ByteBuffer} can be assumed as processed.
     *             Any data not consumed from the {@link ByteBuffer} will be the responsibility of the caller
     *             to resubmit in subsequent calls.
     * @throws IOException if something goes wrong
     */
    protected final void onRead(ByteBuffer data) throws IOException {
        ByteBufferQueue recvQueue = this.recvQueue;
        if (ptr == null) {
            recvQueue.put(data);
        } else {
            if (recvQueue != null && recvQueue.hasRemaining()) {
                recvQueue.put(data);
                flushRecvQueue();
            } else {
                ptr.onRecv(data);
            }
        }
    }

    /**
     * SPI: Notify that the connection with the recipient is closed.
     */
    @OverrideMustInvoke
    protected final void onRecvClosed() {
        if (ptr == null) {
            throw new IllegalStateException("Not initialized");
        } else {
            if (ptr.isRecvOpen()) {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "[{0}] RECV Closed", ptr.stack().name());
                }
                try {
                    ptr.onRecvClosed(new ClosedChannelException());
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    /**
     * Request the recv side to be closed.
     */
    public abstract void doCloseRecv();

    /**
     * SPI: Check if the recipient is open.
     *
     * @return {@code true} if the recipient is open.
     */
    protected final boolean isRecvOpen() {
        if (ptr == null) {
            throw new IllegalStateException("Not initialized");
        }
        return ptr.isRecvOpen();
    }

    /**
     * Flush the receive queue.
     *
     * @throws IOException if something goes wrong.
     */
    private void flushRecvQueue() throws IOException {
        if (recvQueue == null) {
            return;
        }
        ByteBuffer tmp = recvQueue.newByteBuffer();
        while (recvQueue.hasRemaining()) {
            ((Buffer) tmp).clear();
            recvQueue.get(tmp);
            ((Buffer) tmp).flip();
            ptr.onRecv(tmp);
        }
        recvQueue = null;
    }

    /**
     * Flush the send queue.
     *
     * @throws IOException if something goes wrong.
     */
    private void flushSendQueue() throws IOException {
        if (sendQueue == null) {
            return;
        }
        ByteBuffer tmp = sendQueue.newByteBuffer();
        while (sendQueue.hasRemaining()) {
            ((Buffer) tmp).clear();
            sendQueue.get(tmp);
            ((Buffer) tmp).flip();
            while (tmp.hasRemaining()) {
                try {
                    write(tmp);
                } catch (IOException e) {
                    // store what ever we know was not written
                    tmp.compact();
                    sendQueue.unget(tmp);
                    throw e;
                }
            }
        }
        sendQueue = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void init(@NonNull ProtocolStack<?>.Ptr ptr) throws IOException {
        if (this.ptr != null && this.ptr != ptr) {
            throw new IllegalStateException("Already initialized");
        }
        this.ptr = ptr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @OverrideMustInvoke
    public void start() throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] Starting", ptr.stack().name());
        }
        try {
            flushRecvQueue();
            flushSendQueue();
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINEST, "[{0}] Started", ptr.stack().name());
            }
        } catch (IOException e) {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LogRecord record = new LogRecord(Level.FINEST, "[{0}] Could not complete start");
                record.setParameters(new Object[] {ptr.stack().name()});
                record.setThrown(e);
                LOGGER.log(record);
            }
            throw e;
        }
    }

    /**
     * Gets the {@link IOHub} that we are using.
     *
     * @return the {@link IOHub} that we are using.
     */
    @NonNull
    public IOHub getIoHub() {
        return ioHub;
    }

    /**
     * SPI: Acquired a new {@link ByteBuffer} optimally sized for network read/write operations.
     *
     * @return a new {@link ByteBuffer}.
     */
    protected ByteBuffer acquire() {
        return ioHub.acquire(CAPACITY);
    }

    /**
     * SPI: Returns a previously acquired {@link ByteBuffer} to the pool.
     *
     * @param buffer the {@link ByteBuffer}.
     */
    protected void release(ByteBuffer buffer) {
        ioHub.release(buffer);
    }

    /**
     * SPI: Creates a new {@link ByteBuffer} optimally sized for network read/write operations.
     *
     * @return a new {@link ByteBuffer} optimally sized for network read/write operations.
     */
    protected ByteBufferQueue newByteBufferQueue() {
        return new ByteBufferQueue(CAPACITY);
    }

    /**
     * Returns the {@link ProtocolStack} instance that we belong to.
     *
     * @return the {@link ProtocolStack} instance that we belong to.
     */
    protected ProtocolStack<?> stack() {
        return ptr == null ? null : ptr.stack();
    }
}
