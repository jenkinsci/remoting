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
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.OverrideMustInvoke;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.jcip.annotations.GuardedBy;
import org.jenkinsci.remoting.util.ByteBufferQueue;

/**
 * An intermediate {@link ProtocolLayer} in a {@link ProtocolStack}. This layer can be responsible for
 * <ul>
 * <li>Filtering the data stream</li>
 * <li>Initial handshaking</li>
 * <li>Transforming the data stream, e.g. encryption</li>
 * <li>Monitoring the data stream</li>
 * <li>etc.</li>
 * </ul>
 *
 * @since 3.0
 */
public abstract class FilterLayer implements ProtocolLayer, ProtocolLayer.Send, ProtocolLayer.Recv {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(FilterLayer.class.getName());

    /**
     * Our position in the {@link ProtocolStack}
     */
    @GuardedBy("this")
    @Nullable
    private ProtocolStack<?>.Ptr ptr;

    /**
     * Bitfiled that tracks completion of the filter.
     * The {@code 1}'s bit flags calls to {@link #completed()}.
     * The {@code 2}'s bit flags calls to {@link #onSendRemoved()}.
     * The {@code 4}'s bit flags calls to {@link #onRecvRemoved()}.
     * Only when all three bits are set is it safe to set {@link #ptr} to {@code null}.
     */
    @GuardedBy("this")
    private int completionState;

    /**
     * {@inheritDoc}
     */
    @Override
    @OverrideMustInvoke
    public final synchronized void init(@NonNull ProtocolStack<?>.Ptr ptr) throws IOException {
        synchronized (this) {
            if (this.ptr != null && this.ptr != ptr) {
                throw new IllegalStateException("Filter has already been initialized");
            }
            this.ptr = ptr;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws IOException {
        // we implement this method so that implementations can be simpler if they do not need to start anything.
    }

    /**
     * The {@link FilterLayer} implementation calls this to signify that it is now a no-op layer in both directions
     * and can be removed from the {@link ProtocolStack}
     */
    protected final void completed() {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] Completed", stack().name());
        }
        synchronized (this) {
            if (completionState == 7) {
                throw new IllegalStateException("Filter has already been completed");
            }
            completionState |= 1;
            this.ptr.remove();
        }
    }

    /**
     * Callback to notify that no more data will be handled by {@link #doSend(ByteBuffer)} as the send side has been
     * unhooked from the stack.
     */
    /*package*/
    final void onSendRemoved() {
        synchronized (this) {
            completionState |= 2;
            if (completionState == 7) {
                // ok fully removed, we can clear out the reference
                this.ptr = null;
            }
        }
    }

    /**
     * Callback to notify that no more data will be handled by {@link #onRecv(ByteBuffer)} as the receive side has been
     * unhooked from the stack.
     */
    /*package*/
    final void onRecvRemoved() {
        synchronized (this) {
            completionState |= 4;
            if (completionState == 7) {
                // ok fully removed, we can clear out the reference
                this.ptr = null;
            }
        }
    }

    /**
     * The {@link FilterLayer} implementation calls this to signify that a critical error in the stack has occurred
     * and that the stack should be torn down and closed.
     *
     * @param cause the root cause to report.
     */
    protected final void abort(@NonNull IOException cause) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LogRecord record = new LogRecord(Level.FINEST, "[{0}] Aborted");
            record.setParameters(new Object[] {stack().name()});
            record.setThrown(cause);
            LOGGER.log(record);
        }
        ProtocolStack<?>.Ptr ptr;
        synchronized (this) {
            ptr = this.ptr;
        }
        if (ptr == null) {
            throw new IllegalStateException();
        }
        // we are aborting the protocol, so there will not be anything received.
        try {
            onRecvClosed(cause);
        } catch (IOException e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LogRecord record = new LogRecord(Level.FINE, "[{0}] Close notification only partially completed");
                record.setParameters(new Object[] {stack().name()});
                record.setThrown(e);
                LOGGER.log(record);
            }
        }
    }

    /**
     * Accessor for the {@link ProtocolStack} that we are bound to.
     *
     * @return the {@link ProtocolStack} that we are bound to or {@code null} if we are not currently bound to a stack.
     */
    @Nullable
    protected ProtocolStack<?> stack() {
        ProtocolStack<?>.Ptr ptr;
        synchronized (this) {
            ptr = this.ptr;
        }
        return ptr == null ? null : ptr.stack();
    }

    /**
     * Accessor for the next layers in the {@link ProtocolStack}.
     *
     * @return our {@link ProtocolStack.Ptr} or {@code null} if we are not currently bound to a stack.
     */
    @Nullable
    protected synchronized ProtocolStack<?>.Ptr next() {
        return ptr;
    }

    /**
     * Flushes the supplied {@link ByteBufferQueue} to {@link #next()}'s {@link ProtocolStack.Ptr#onRecv(ByteBuffer)}.
     * This method is especially helpful for {@link FilterLayer} implementations that are involved in initial
     * handshaking as they will need to queue up data until the handshake is completed and then flush the data to
     * the remainder of the stack.
     *
     * @param queue the data to receive.
     * @throws IOException if there is an I/O error during the receive.
     */
    protected final void flushRecv(ByteBufferQueue queue) throws IOException {
        ProtocolStack<?>.Ptr ptr;
        synchronized (this) {
            ptr = this.ptr;
        }
        if (ptr == null) {
            throw new IllegalStateException();
        }
        ByteBuffer tmp = queue.newByteBuffer();
        while (queue.hasRemaining()) {
            ((Buffer) tmp).clear();
            queue.get(tmp);
            ((Buffer) tmp).flip();
            try {
                ptr.onRecv(tmp);
            } catch (IOException e) {
                queue.unget(tmp);
                throw e;
            }
        }
    }

    /**
     * Flushes the supplied {@link ByteBufferQueue} to {@link #next()}'s {@link ProtocolStack.Ptr#doSend(ByteBuffer)}
     * This method is especially helpful for {@link FilterLayer} implementations that are involved in initial
     * handshaking as they will need to queue up data until the handshake is completed and then flush the data to
     * the remainder of the stack.
     *
     * @param queue the data to send.
     * @throws IOException if there is an I/O error during the receive.
     */
    protected final void flushSend(ByteBufferQueue queue) throws IOException {
        ProtocolStack<?>.Ptr ptr;
        synchronized (this) {
            ptr = this.ptr;
        }
        if (ptr == null) {
            throw new IllegalStateException();
        }
        ByteBuffer tmp = queue.newByteBuffer();
        while (queue.hasRemaining()) {
            ((Buffer) tmp).clear();
            queue.get(tmp);
            ((Buffer) tmp).flip();
            try {
                ptr.doSend(tmp);
            } catch (IOException e) {
                queue.unget(tmp);
                throw e;
            }
        }
    }

    /**
     * SPI: Callback on data being received from the lower layer.
     *
     * @param data the data received. Any data consumed from the {@link ByteBuffer} can be assumed as processed.
     *             Any data not consumed from the {@link ByteBuffer} will be the responsibility of the caller
     *             to resubmit in subsequent calls.
     * @throws IOException if there was an error during processing of the received data.
     */
    @Override
    public abstract void onRecv(@NonNull ByteBuffer data) throws IOException;

    /**
     * {@inheritDoc}
     */
    @Override
    @OverrideMustInvoke
    public void onRecvClosed(IOException cause) throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] RECV Closed", stack().name());
        }
        ProtocolStack<?>.Ptr ptr;
        synchronized (this) {
            ptr = this.ptr;
        }
        if (ptr == null) {
            throw new IllegalStateException();
        }
        ptr.onRecvClosed(cause);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRecvOpen() {
        ProtocolStack<?>.Ptr ptr;
        synchronized (this) {
            ptr = this.ptr;
        }
        return ptr != null && ptr.isRecvOpen();
    }

    /**
     * SPI: Sends data to the lower layer.
     *
     * @param data the data to send. Any data consumed from the {@link ByteBuffer} can be assumed as processed.
     *             Any data not consumed from the {@link ByteBuffer} will be the responsibility of the caller
     *             to resubmit in subsequent calls.
     * @throws IOException if there was an error during processing of the data.
     */
    @Override
    public abstract void doSend(@NonNull ByteBuffer data) throws IOException;

    /**
     * {@inheritDoc}
     */
    @Override
    @OverrideMustInvoke
    public void doCloseSend() throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] Closing SEND", stack().name());
        }
        ProtocolStack<?>.Ptr ptr;
        synchronized (this) {
            ptr = this.ptr;
        }
        if (ptr == null) {
            throw new IllegalStateException();
        }
        ptr.doCloseSend();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSendOpen() {
        ProtocolStack<?>.Ptr ptr;
        synchronized (this) {
            ptr = this.ptr;
        }
        return ptr != null && ptr.isSendOpen();
    }
}
