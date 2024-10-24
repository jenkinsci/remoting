/*
 * Copyright (c) 2016, Stephen Connolly, CloudBees, Inc., and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.remoting.protocol.impl;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.security.GeneralSecurityException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import org.jenkinsci.remoting.protocol.FilterLayer;
import org.jenkinsci.remoting.util.ByteBufferUtils;
import org.jenkinsci.remoting.util.ThrowableUtils;

/**
 * A {@link FilterLayer} that encrypts the communication between the upper layers and the lower layers using
 * the supplied {@link SSLEngine}.
 *
 * @since 3.0
 */
public class SSLEngineFilterLayer extends FilterLayer {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(SSLEngineFilterLayer.class.getName());

    /**
     * The {@link SSLEngine} to use.
     */
    @NonNull
    private final SSLEngine sslEngine;
    /**
     * The {@link Listener} to notify on successful handshaking.
     */
    @CheckForNull
    private final Listener listener;
    /**
     * Lock to guard against concurrent calls to {@link SSLEngine#wrap(ByteBuffer, ByteBuffer)}. The lock is required
     * as calls to wrap can originate from both reads and writes.
     */
    private final Object wrapLock = new Object();
    /**
     * The state of the connection
     */
    @NonNull
    private State state = State.CREDENTIALS_NOT_YET_AVAILABLE;
    /**
     * The queue of messages to send, populated while waiting on handshaking to complete.
     */
    @NonNull
    private final ConcurrentLinkedQueue<ByteBuffer> messages = new ConcurrentLinkedQueue<>();
    /**
     * Buffer to hold any partial reads until we have a complete SSL record.
     */
    @CheckForNull
    private ByteBuffer previous;

    private final AtomicReference<ByteBuffer> directBufferRef = new AtomicReference<>();

    /**
     * Constructs a new instance.
     *
     * @param engine   the engine to use.
     * @param listener the listener to notify when handshaking is completed.
     */
    public SSLEngineFilterLayer(@NonNull SSLEngine engine, @CheckForNull Listener listener) {
        this.sslEngine = engine;
        this.listener = listener;
        previous = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "{0} Starting {1}", new Object[] {stack().name(), sslEngine.getHandshakeStatus()});
        }
        sslEngine.beginHandshake();
        onRecv(EMPTY_BUFFER);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRecv(@NonNull ByteBuffer readBuffer) throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] RECV: {1} bytes plus {2} retained", new Object[] {
                stack().name(), readBuffer.remaining(), previous == null ? 0 : previous.remaining()
            });
        }
        try {
            processRead(readBuffer);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            while (cause instanceof RuntimeException) {
                cause = cause.getCause();
            }
            if (cause instanceof GeneralSecurityException) {
                if (LOGGER.isLoggable(Level.SEVERE)) {
                    LOGGER.log(Level.SEVERE, "[" + stack().name() + "] ", e);
                }
                abort(new IOException(cause));
            } else {
                if (LOGGER.isLoggable(Level.WARNING)) {
                    LOGGER.log(Level.WARNING, "[" + stack().name() + "] ", e);
                }
                throw e;
            }
        } catch (SSLException e) {
            if (LOGGER.isLoggable(Level.SEVERE)) {
                LOGGER.log(Level.SEVERE, "[" + stack().name() + "] ", e);
            }
            abort(e);
        } catch (ClosedChannelException | ConnectionRefusalException e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "[" + stack().name() + "] ", e);
            }
        } catch (IOException e) {
            if (LOGGER.isLoggable(Level.WARNING)) {
                LOGGER.log(Level.WARNING, "[" + stack().name() + "] ", e);
            }
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRecvClosed(IOException cause) throws IOException {
        if (!sslEngine.isInboundDone() && isSendOpen()) {
            IOException ioe = null;
            try {
                sslEngine.closeInbound();
                doSend(EMPTY_BUFFER);
            } catch (IOException e) {
                ioe = e;
                throw e;
            } finally {
                try {
                    super.onRecvClosed(cause);
                } catch (IOException e) {
                    if (ioe != null) {
                        ThrowableUtils.chain(e, ioe);
                    }
                    throw e;
                }
            }
        } else {
            super.onRecvClosed(cause);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRecvOpen() {
        return !sslEngine.isInboundDone() && super.isRecvOpen();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void doSend(@NonNull ByteBuffer message) throws IOException {
        messages.add(ByteBufferUtils.duplicate(message));
        if (State.CREDENTAILS_AVAILABLE.equals(state)) {
            processQueuedWrites();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void doCloseSend() throws IOException {
        if (!sslEngine.isOutboundDone() && isSendOpen()) {
            IOException ioe = null;
            try {
                sslEngine.closeOutbound();
                doSend(EMPTY_BUFFER);
            } catch (IOException e) {
                ioe = e;
                throw e;
            } finally {
                try {
                    super.doCloseSend();
                } catch (IOException e) {
                    if (ioe != null) {
                        ThrowableUtils.chain(e, ioe);
                    }
                    throw e;
                }
            }
        } else {
            super.doCloseSend();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSendOpen() {
        return !sslEngine.isOutboundDone() && super.isSendOpen();
    }

    /**
     * Push any queued writes through the SSLEngine.
     */
    private void processQueuedWrites() {
        synchronized (wrapLock) {
            ByteBuffer request;
            while (null != (request = messages.poll())) {
                try {
                    processWrite(request);
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    /**
     * Notification of the initial handshake starting.
     */
    private void processHandshakeStarted() {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "[{0}] Handshake started", stack().name());
        }
    }

    /**
     * Notification of the handshake completion.
     */
    private void processHandshakeCompleted() throws ConnectionRefusalException {
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(Level.FINE, "[{0}] Handshake completed", stack().name());
        }
        if (listener != null) {
            listener.onHandshakeCompleted(sslEngine.getSession());
        }
    }

    /**
     * Record the state as having stopped being encrypted by {@link SSLEngine}.
     */
    private void switchToNoSecure() {
        try {
            state = State.NO_CREDENTIALS;
            onRecvClosed(null);
        } catch (IOException e) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LogRecord record = new LogRecord(
                        Level.FINE, "[{0}] Could not complete close of read after closure of SSL session");
                record.setParameters(new Object[] {stack().name()});
                record.setThrown(e);
                LOGGER.log(record);
            }
        }
    }

    /**
     * A listener for the connection headers.
     */
    public interface Listener {

        /**
         * Callback when the handshake is completed.
         *
         * @param session the ssl session
         * @throws ConnectionRefusalException if the remote connection is rejected.
         */
        void onHandshakeCompleted(SSLSession session) throws ConnectionRefusalException;
    }

    // -----------------------------------------------------------------------------------------------------------------

    //
    // The following code is a modified version of the code from Apache MINA's SslHelper class.
    //
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Process the read.
     *
     * @param readBuffer the read buffer to pull data from.
     * @throws IOException if there was an error reading data.
     */
    private void processRead(@NonNull ByteBuffer readBuffer) throws IOException {
        ByteBuffer tempBuffer;

        if (previous != null) {
            tempBuffer = previous = ByteBufferUtils.accumulate(readBuffer, previous);
        } else {
            tempBuffer = readBuffer;
        }

        boolean done = false;
        SSLEngineResult result;
        ByteBuffer appBuffer = stack().acquire(sslEngine.getSession().getApplicationBufferSize());

        SSLEngineResult.HandshakeStatus handshakeStatus = sslEngine.getHandshakeStatus();
        while (!done) {
            switch (handshakeStatus) {
                case NEED_UNWRAP: // $FALL-THROUGH$
                case NOT_HANDSHAKING: // $FALL-THROUGH$
                case FINISHED:
                    ((Buffer) appBuffer).clear();
                    if (!tempBuffer.hasRemaining() && handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                        /* we need more data */
                        done = true;
                        break;
                    }
                    result = sslEngine.unwrap(tempBuffer, appBuffer);
                    processResult(handshakeStatus, result);

                    switch (result.getStatus()) {
                        case BUFFER_UNDERFLOW:
                            /* we need more data */
                        case CLOSED:
                            /* connection is already closed */
                            done = true;
                            break;
                        case BUFFER_OVERFLOW:
                            /* resize output buffer */
                            int newCapacity = appBuffer.capacity() * 2;
                            stack().release(appBuffer);
                            appBuffer = stack().acquire(newCapacity);
                            break;
                        case OK:
                            if ((handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
                                    && (result.bytesProduced() > 0)) {
                                ((Buffer) appBuffer).flip();
                                if (LOGGER.isLoggable(Level.FINEST)) {
                                    LOGGER.log(Level.FINEST, "[{0}] DECODE: {1} bytes", new Object[] {
                                        stack().name(), appBuffer.remaining()
                                    });
                                }
                                next().onRecv(appBuffer);
                                ((Buffer) appBuffer).clear();
                            }
                            break;
                    }
                    handshakeStatus = sslEngine.getHandshakeStatus();
                    break;
                case NEED_TASK:
                    Runnable task;

                    while ((task = sslEngine.getDelegatedTask()) != null) {
                        task.run();
                    }
                    handshakeStatus = sslEngine.getHandshakeStatus();
                    break;
                case NEED_WRAP:
                    synchronized (wrapLock) {
                        ((Buffer) appBuffer).clear();
                        if (LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.log(Level.FINEST, "[{0}] HANDSHAKE ENCODE: 0 bytes", stack().name());
                        }
                        result = sslEngine.wrap(EMPTY_BUFFER, appBuffer);
                        // put processResult back here once we find a better way to enqueue the pending writes
                        switch (result.getStatus()) {
                            case BUFFER_OVERFLOW:
                                int newCapacity = appBuffer.capacity() * 2;
                                stack().release(appBuffer);
                                appBuffer = stack().acquire(newCapacity);
                                break;
                            case BUFFER_UNDERFLOW:
                                done = true;
                                break;
                            case CLOSED: // $FALL-THROUGH$
                            case OK:
                                ((Buffer) appBuffer).flip();
                                if (appBuffer.hasRemaining()) {
                                    if (LOGGER.isLoggable(Level.FINEST)) {
                                        LOGGER.log(Level.FINEST, "[{0}] HANDSHAKE SEND: {1} bytes", new Object[] {
                                            stack().name(), appBuffer.remaining()
                                        });
                                    }
                                    next().doSend(appBuffer);
                                }
                                break;
                        }
                    }
                    processResult(handshakeStatus, result);
                    handshakeStatus = sslEngine.getHandshakeStatus();
            }
            if (handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                state = State.CREDENTAILS_AVAILABLE;
            }
        }
        stack().release(appBuffer);
        if (tempBuffer.remaining() > 0) {
            previous = ByteBufferUtils.duplicate(tempBuffer);
        } else {
            previous = null;
        }
    }

    /**
     * Process the session handshake status and the last operation result in order to
     * update the internal state and propagate handshake related events.
     *
     * @param sessionStatus   the last session handshake status
     * @param operationStatus the returned operation status
     */
    private void processResult(SSLEngineResult.HandshakeStatus sessionStatus, SSLEngineResult operationStatus) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] Handshake status: {1} engine result: {2}", new Object[] {
                stack().name(), sessionStatus, operationStatus
            });
        }
        switch (sessionStatus) {
            case NEED_TASK: // $FALL-THROUGH$
            case NEED_UNWRAP: // $FALL-THROUGH$
            case NEED_WRAP:
                if (operationStatus.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                    synchronized (wrapLock) {
                        if (state == State.CREDENTIALS_NOT_YET_AVAILABLE) {
                            state = State.CREDENTAILS_AVAILABLE;
                            try {
                                processHandshakeCompleted();
                            } catch (ConnectionRefusalException e) {
                                abort(e);
                                break;
                            }
                        }
                        processQueuedWrites();
                    }
                }
                if (operationStatus.getStatus() == SSLEngineResult.Status.CLOSED) {
                    switchToNoSecure();
                }
                break;
            case FINISHED: // $FALL-THROUGH$
            case NOT_HANDSHAKING:
                if (operationStatus.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    if (state == State.CREDENTIALS_NOT_YET_AVAILABLE) {
                        processHandshakeStarted();
                    }
                }
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + sessionStatus);
        }
    }

    /**
     * Process the write through the SSLEngine.
     *
     * @param message the data to write.
     * @throws IOException if something goes wrong.
     */
    private void processWrite(@NonNull ByteBuffer message) throws IOException {
        ByteBuffer appBuffer = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        boolean done = false;
        while (!done) {
            // Encrypt the message
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(
                        Level.FINEST, "[{0}] APP ENCODE: {1} bytes", new Object[] {stack().name(), message.remaining()
                        });
            }
            SSLEngineResult result = sslEngine.wrap(message, appBuffer);
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINEST, "[{0}] Handshake status: {1} engine result: {2}", new Object[] {
                    stack().name(), result.getHandshakeStatus(), result
                });
            }

            switch (result.getStatus()) {
                case BUFFER_OVERFLOW:
                    // Increase the buffer size as needed
                    appBuffer = ByteBuffer.allocate(appBuffer.capacity() + 4096);
                    break;
                case CLOSED:
                    switchToNoSecure();
                    done = true;
                    break;

                case BUFFER_UNDERFLOW: // $FALL-THROUGH$
                case OK:
                    // We are done. Flip the buffer and push it to the write queue.
                    ((Buffer) appBuffer).flip();
                    done = !message.hasRemaining();
                    if (appBuffer.hasRemaining()) {
                        if (LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.log(Level.FINEST, "[{0}] APP SEND: {1} bytes", new Object[] {
                                stack().name(), appBuffer.remaining()
                            });
                        }
                        while (appBuffer.hasRemaining()) {
                            next().doSend(appBuffer);
                        }
                    }
                    break;
            }
            if (!done) {
                ((Buffer) appBuffer).clear();
            }
        }
    }

    /**
     * The internal secure state of the session.
     */
    enum State {
        /**
         * The session is currently handskaking, application messages will be queued before being encrypted and sent.
         */
        CREDENTIALS_NOT_YET_AVAILABLE,
        /**
         * The session has completed handshake, application messagescan be encrypted and sent as they are submitted.
         */
        CREDENTAILS_AVAILABLE,
        /**
         * Secure credentials are removed from the session, application messages are not encrypted anymore.
         */
        NO_CREDENTIALS
    }
}
