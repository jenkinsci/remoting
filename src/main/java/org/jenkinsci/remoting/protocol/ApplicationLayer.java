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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.jcip.annotations.GuardedBy;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * The highest {@link ProtocolLayer} in a {@link ProtocolStack}. This layer is responsible for translating the protocol stack
 * into the application specific API.
 *
 * @param <T> the application specific API.
 *
 * @since 3.0
 */
public abstract class ApplicationLayer<T> implements ProtocolLayer, ProtocolLayer.Recv {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ApplicationLayer.class.getName());
    /**
     * Lock to guard access to the {@link #sendClosed} and {@link #recvClosed} fields.
     */
    private final Object closedLock = new Object();
    /**
     * Our {@link ProtocolStack.Ptr}.
     */
    private ProtocolStack<?>.Ptr ptr;
    /**
     * Flag to track that the send path is closed.
     */
    @GuardedBy("closedLock")
    private boolean sendClosed;
    /**
     * Flag to track that the recv path is closed.
     */
    @GuardedBy("closedLock")
    private boolean recvClosed;

    /**
     * SPI: Returns the application specific API instance.
     *
     * @return the application specific API instance.
     */
    public abstract T get();

    /**
     * SPI: Implementations of {@link ApplicationLayer} must ensure this method either returns {@code true} while the
     * application specific API instance ({@link #get}) is accepting data via {@link #onRead(ByteBuffer)} or
     * returns {@code false} once it is permanently closed to incoming data. If the application specific API instance
     * is temporarily not accepting data then this method should return {@code true} and the implementation is
     * responsible for caching the data submitted in calls to {@link #onRead(ByteBuffer)}
     * Once this method returns {@code false} it must always return {@code false} and can be assumed to behave in
     * this way.
     *
     * @return {@code true} if the application specific API instance ({@link #get()} is accepting data via
     * {@link #onRead(ByteBuffer)}
     */
    public abstract boolean isReadOpen();

    /**
     * SPI: Callback on data being received from the protocol stack.
     *
     * @param data the data received. Any data consumed from the {@link ByteBuffer} can be assumed as processed.
     *             Any data not consumed from the {@link ByteBuffer} will be the responsibility of the caller
     *             to resubmit in subsequent calls.
     * @throws IOException if there was an error during processing of the received data.
     */
    public abstract void onRead(@NonNull ByteBuffer data) throws IOException;

    /**
     * Callback on the lower layer's source of data being closed.
     *
     * @param cause the cause of the lower layer being closed or {@code null}.
     * @throws IOException if there was an error during the processing of the close notification.
     */
    public abstract void onReadClosed(IOException cause) throws IOException;

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
     * SPI: Implementations of {@link ApplicationLayer} should use method to detect if the {@link ProtocolStack} is
     * open for writing via {@link #write(ByteBuffer)} or has been closed.
     * Once this method returns {@code false} it will always return {@code false} and can be assumed to behave in
     * this way.
     *
     * @return {@code true} if the {@link ProtocolStack} is open for writing via {@link #write(ByteBuffer)}.
     */
    public final boolean isWriteOpen() {
        if (ptr == null) {
            throw new IllegalStateException();
        }
        synchronized (closedLock) {
            if (sendClosed) {
                return false;
            }
        }
        if (ptr.isSendOpen()) {
            return true;
        }
        synchronized (closedLock) {
            sendClosed = true;
            return false;
        }
    }

    /**
     * SPI: Implementations of {@link ApplicationLayer} should use this method to write data through the
     * {@link ProtocolStack}.
     *
     * @param data the data to write. Any data consumed from the {@link ByteBuffer} can be assumed as processed.
     *             Any data not consumed from the {@link ByteBuffer} will be the responsibility of the caller
     *             to resubmit in subsequent calls.
     * @throws IOException if there was an error during processing of the data.
     */
    public final void write(@NonNull ByteBuffer data) throws IOException {
        ptr.doSend(data);
    }

    /**
     * SPI: Implementations of {@link ApplicationLayer} should use this method to request that the write path of the
     * {@link ProtocolStack} be closed. Depending on the nature of the {@link NetworkLayer} this may force closed
     * the read path (e.g. if the backing transport is using a {@link SocketChannel}).
     *
     * @throws IOException if there was an error during the closing of the write path.
     */
    public final void doCloseWrite() throws IOException {
        synchronized (closedLock) {
            sendClosed = true;
        }
        if (ptr.isSendOpen()) {
            LOGGER.log(Level.FINE, "[{0}] Closing SEND", ptr.stack().name());
            ptr.doCloseSend();
        }
    }

    /**
     * SPI: Implementations of {@link ApplicationLayer} should use this method to request that the read path of the
     * {@link ProtocolStack} be closed. Depending on the nature of the {@link NetworkLayer} this may force closed
     * the write path (e.g. if the backing transport is using a {@link SocketChannel}).
     *
     * @throws IOException if there was an error during the closing of the read path.
     */
    public final void doCloseRead() throws IOException {
        if (ptr.stack().isRecvOpen()) {
            LOGGER.log(Level.FINE, "[{0}] Closing RECV", ptr.stack().name());
            ptr.stack().doCloseRecv();
        }
    }

    /**
     * Accessor for the {@link ProtocolStack} that we are bound to.
     *
     * @return the {@link ProtocolStack} that we are bound to or {@code null} if we are not currently bound to a stack.
     */
    @Nullable
    protected ProtocolStack<?> stack() {
        return ptr == null ? null : ptr.stack();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Restricted(NoExternalUse.class)
    public final void onRecv(@NonNull ByteBuffer data) throws IOException {
        onRead(data);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Restricted(NoExternalUse.class)
    public final void onRecvClosed(IOException cause) throws IOException {
        LOGGER.log(Level.FINE, "[{0}] RECV Closed", ptr.stack().name());
        synchronized (closedLock) {
            recvClosed = true;
        }
        IOException ioe = null;
        try {
            onReadClosed(cause);
            stack().onClosed(cause);
        } catch (IOException e) {
            ioe = e;
        } finally {
            try {
                doCloseWrite();
            } catch (IOException e) {
                if (ioe != null) {
                    ioe.addSuppressed(e);
                    throw ioe;
                }
                throw e;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Restricted(NoExternalUse.class)
    public final boolean isRecvOpen() {
        synchronized (closedLock) {
            if (recvClosed) {
                return false;
            }
        }
        if (isReadOpen()) {
            return true;
        }
        synchronized (closedLock) {
            recvClosed = true;
            return false;
        }
    }
}
