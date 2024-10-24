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
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.protocol.FilterLayer;
import org.jenkinsci.remoting.protocol.ProtocolStack;
import org.jenkinsci.remoting.util.ByteBufferQueue;
import org.jenkinsci.remoting.util.ByteBufferUtils;
import org.jenkinsci.remoting.util.ThrowableUtils;

/**
 * Performs the connection header negotiation.
 *
 * @since 3.0
 */
public class ConnectionHeadersFilterLayer extends FilterLayer {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ConnectionHeadersFilterLayer.class.getName());
    /**
     * The abort confirmation message.
     */
    private static final ByteBuffer ABORT_MESSAGE =
            ByteBufferUtils.wrapUTF8("BYE").asReadOnlyBuffer();
    /**
     * The headers to send.
     */
    private final ByteBuffer headerOutput;
    /**
     * The response to send.
     */
    private ByteBuffer responseOutput;
    /**
     * The length of the headers to receive.
     */
    private final ByteBuffer headerInputLength;
    /**
     * The content of the headers to receive.
     */
    private ByteBuffer headerInputContent;
    /**
     * The length of the response to receive.
     */
    private ByteBuffer responseInputLength;
    /**
     * The content of the response to receive.
     */
    private ByteBuffer responseInputContent;
    /**
     * Buffer to hold the confirmation of an {@literal ERROR} response
     */
    private ByteBuffer abortConfirmationInput;
    /**
     * The abort cause to set once EITHER the confirmation of receipt of the {@literal ERROR} has been received
     * OR the {@link #abortConfirmationTimeout} has expired.
     */
    private ConnectionRefusalException abortCause;
    /**
     * We do not wait forever for the {@link #abortConfirmationInput}.
     */
    private Future<?> abortConfirmationTimeout;
    /**
     * The queue of data to {@link ProtocolStack.Ptr#doSend(ByteBuffer)} on {@link #next()}, populated while we await
     * the complete response cycle.
     */
    private final ByteBufferQueue sendQueue = new ByteBufferQueue(8192);
    /**
     * The queue of data to {@link ProtocolStack.Ptr#onRecv(ByteBuffer)} on {@link #next()}, populated while we await
     * the complete response cycle.
     */
    private final ByteBufferQueue recvQueue = new ByteBufferQueue(8192);
    /**
     * The {@link Listener} to decide the response to the received headers.
     */
    private final Listener listener;
    /**
     * Flag to signify that we have completed.
     */
    private boolean finished;
    /**
     * Marker for the abort reason
     */
    private final AtomicReference<ConnectionRefusalException> aborted = new AtomicReference<>();

    /**
     * Constructor.
     *
     * @param headers  Our headers to send.
     * @param listener Our listener to decide the response to the remote headers.
     */
    public ConnectionHeadersFilterLayer(Map<String, String> headers, Listener listener) {
        this.headerOutput = ByteBufferUtils.wrapUTF8(ConnectionHeaders.toString(headers));
        this.responseOutput = null;
        this.listener = listener;
        this.headerInputLength = ByteBuffer.allocate(2);
        this.headerInputContent = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void start() {
        try {
            doSend(EMPTY_BUFFER);
        } catch (IOException e) {
            // ignore
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRecv(@NonNull ByteBuffer data) throws IOException {
        final ConnectionRefusalException aborted = this.aborted.get();
        if (aborted != null) {
            throw newAbortCause(aborted);
        }
        synchronized (this) {
            if (headerInputLength.hasRemaining()) {
                ByteBufferUtils.put(data, headerInputLength);
                if (headerInputLength.hasRemaining()) {
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.log(Level.FINEST, "[{0}] expecting {1} more bytes of header length", new Object[] {
                            stack().name(), headerInputLength.remaining()
                        });
                    }
                    return;
                }
                ((Buffer) headerInputLength).flip();
                int length = this.headerInputLength.asShortBuffer().get() & 0xffff;
                ((Buffer) headerInputLength).position(2);
                headerInputContent = ByteBuffer.allocate(length);
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(
                            Level.FINEST, "[{0}] Expecting {1} bytes of headers", new Object[] {stack().name(), length
                            });
                }
            }
            // safe-point
            if (!data.hasRemaining()) {
                return;
            }
            if (headerInputContent.hasRemaining()) {
                ByteBufferUtils.put(data, headerInputContent);
                if (headerInputContent.hasRemaining()) {
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.log(Level.FINEST, "[{0}] Expecting {1} more bytes of headers", new Object[] {
                            stack().name(), headerInputContent.remaining()
                        });
                    }
                    return;
                }
                byte[] headerBytes = new byte[headerInputContent.capacity()];
                ((Buffer) headerInputContent).flip();
                headerInputContent.get(headerBytes, 0, headerInputContent.remaining());
                final String headerAsString = new String(headerBytes, StandardCharsets.UTF_8);
                if (LOGGER.isLoggable(Level.FINER)) {
                    LOGGER.log(
                            Level.FINER, "[{0}] Received headers \"{1}\"", new Object[] {stack().name(), headerAsString
                            });
                }
                try {
                    Map<String, String> headers = ConnectionHeaders.fromString(headerAsString);
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE, "[{0}] Received headers {1}", new Object[] {stack().name(), headers});
                    }
                    listener.onReceiveHeaders(headers);
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE, "[{0}] Accepting headers from remote", stack().name());
                    }
                } catch (ConnectionHeaders.ParseException e) {
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.log(
                                Level.WARNING,
                                "[{0}] Remote headers \"{1}\" could not be parsed: {2}",
                                new Object[] {stack().name(), headerAsString, e.getMessage()});
                    }
                    responseOutput = ByteBufferUtils.wrapUTF8("ERROR: Malformed connection header");
                    if (this.headerOutput.hasRemaining()) {
                        // flush any headers we haven't sent yet as the other side is expecting them.
                        next().doSend(this.headerOutput);
                    }
                    doStartAbort(
                            new ConnectionRefusalException("Malformed connection header"),
                            ByteBuffer.allocate(ABORT_MESSAGE.capacity()));
                    next().doSend(responseOutput);
                    return;
                } catch (ConnectionRefusalException e) {
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.log(Level.INFO, "[{0}] {1} headers from remote: {2}", new Object[] {
                            stack().name(),
                            e instanceof PermanentConnectionRefusalException ? "Permanently refusing" : "Refusing",
                            e.getMessage()
                        });
                    }
                    responseOutput = ByteBufferUtils.wrapUTF8(String.format(
                            "%s: %s",
                            e instanceof PermanentConnectionRefusalException ? "FATAL" : "ERROR", e.getMessage()));
                    if (this.headerOutput.hasRemaining()) {
                        // flush any headers we haven't sent yet as the other side is expecting them.
                        next().doSend(this.headerOutput);
                    }
                    doStartAbort(e, ByteBuffer.allocate(ABORT_MESSAGE.capacity()));
                    next().doSend(responseOutput);
                    return;
                }
                responseOutput = ByteBufferUtils.wrapUTF8("OK");
                if (this.headerOutput.hasRemaining()) {
                    // flush any headers we haven't sent yet as the other side is expecting them.
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.log(Level.FINEST, "[{0}] Sending {1} bytes of headers", new Object[] {
                            stack().name(), this.headerOutput.remaining()
                        });
                    }
                    next().doSend(this.headerOutput);
                }
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "[{0}] Sending {1} bytes of response", new Object[] {
                        stack().name(), this.responseOutput.remaining()
                    });
                }
                next().doSend(responseOutput);
                responseInputLength = ByteBuffer.allocate(2);
            }
            // safe-point
            if (!data.hasRemaining()) {
                return;
            }
            assert abortConfirmationInput != null || responseInputLength != null;
            if (abortConfirmationInput != null) {
                // we are awaiting abort confirmation
                if (abortConfirmationInput.hasRemaining()) {
                    ByteBufferUtils.put(data, abortConfirmationInput);
                }
                if (abortConfirmationInput.hasRemaining()) {
                    return;
                }
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "[{0}] Received confirmation of {1} headers", new Object[] {
                        stack().name(),
                        abortCause instanceof PermanentConnectionRefusalException ? "permanently refused" : "refused"
                    });
                }
                abortConfirmationTimeout.cancel(false);
                onAbortCompleted();
                throw abortCause;
            }
            if (responseInputLength.hasRemaining()) {
                ByteBufferUtils.put(data, responseInputLength);
                if (responseInputLength.hasRemaining()) {
                    return;
                }
                ((Buffer) this.responseInputLength).flip();
                int length = this.responseInputLength.asShortBuffer().get() & 0xffff;
                ((Buffer) this.responseInputLength).position(2);
                responseInputContent = ByteBuffer.allocate(length);
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(
                            Level.FINEST, "[{0}] Expecting {1} bytes of response", new Object[] {stack().name(), length
                            });
                }
            }
            // safe-point
            if (!data.hasRemaining()) {
                return;
            }
            if (responseInputContent.hasRemaining()) {
                ByteBufferUtils.put(data, responseInputContent);
                if (responseInputContent.hasRemaining()) {
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.log(Level.FINEST, "[{0}] Expecting {1} more bytes of response", new Object[] {
                            stack().name(), responseInputContent.remaining()
                        });
                    }
                    return;
                }
                byte[] responseBytes = new byte[responseInputContent.capacity()];
                ((Buffer) responseInputContent).flip();
                responseInputContent.get(responseBytes, 0, responseInputContent.remaining());
                String response = new String(responseBytes, StandardCharsets.UTF_8);
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "[{0}] Received response \"{1}\"", new Object[] {stack().name(), response});
                }
                finished = true;
                if (response.startsWith("ERROR: ")) {
                    String message = response.substring("ERROR: ".length());
                    if (LOGGER.isLoggable(Level.INFO)) {
                        LOGGER.log(Level.INFO, "[{0}] Local headers refused by remote: {1}", new Object[] {
                            stack().name(), message
                        });
                    }
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.log(Level.FINEST, "[{0}] Confirming receipt of refused connection: {1}", new Object[] {
                            stack().name(), message
                        });
                    }
                    next().doSend(ABORT_MESSAGE.duplicate());
                    doStartAbort(new ConnectionRefusalException(message), EMPTY_BUFFER);
                    return;
                }
                if (response.startsWith("FATAL: ")) {
                    String message = response.substring("FATAL: ".length());
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.log(
                                Level.WARNING,
                                "[{0}] Local headers permanently rejected by remote: {1}",
                                new Object[] {stack().name(), message});
                    }
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.log(
                                Level.FINEST,
                                "[{0}] Confirming receipt of permanently rejected connection: {1}",
                                new Object[] {stack().name(), message});
                    }
                    next().doSend(ABORT_MESSAGE.duplicate());
                    doStartAbort(new PermanentConnectionRefusalException(message), EMPTY_BUFFER);
                    return;
                }
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "[{0}] Local headers accepted by remote", stack().name());
                }
            }
            if (sendQueue.hasRemaining()) {
                try {
                    flushSend(sendQueue);
                } catch (IOException e) {
                    recvQueue.put(data);
                    throw e;
                }
            }
            if (recvQueue.hasRemaining()) {
                recvQueue.put(data);
                flushRecv(recvQueue);
            } else if (data.hasRemaining()) {
                next().onRecv(data);
            }
            if (!sendQueue.hasRemaining() && !recvQueue.hasRemaining()) {
                complete();
            }
        }
    }

    protected void complete() {
        if (LOGGER.isLoggable(Level.FINE)) {
            String name = stack().name();
            completed();
            LOGGER.log(Level.FINE, "[{0}] Connection header exchange completed", name);
        } else {
            completed();
        }
    }

    /**
     * Create a new {@link ConnectionRefusalException} from the supplied cause.
     * @param abortCause the supplied cause.
     * @return a {@link ConnectionRefusalException}
     */
    private static ConnectionRefusalException newAbortCause(ConnectionRefusalException abortCause) {
        return abortCause instanceof PermanentConnectionRefusalException
                ? new PermanentConnectionRefusalException(abortCause.getMessage())
                : new ConnectionRefusalException(abortCause.getMessage());
    }

    /**
     * Switches the stack into the aborting state.
     * @param cause the cause of the abort.
     * @param buffer the expected confirmation (either {@link #ABORT_MESSAGE} or {@link #EMPTY_BUFFER} depending
     *               on whether we are initiating the abort or confirming the abort respectively.
     */
    private synchronized void doStartAbort(ConnectionRefusalException cause, ByteBuffer buffer) {
        ProtocolStack<?> stack = stack();
        abortConfirmationInput = buffer;
        abortCause = cause;
        abortConfirmationTimeout =
                stack.executeLater(new Aborter(), stack.getHandshakingTimeout(), stack.getHandshakingUnits());
    }

    /**
     * Finalizes the abort of the connection.
     */
    private synchronized void onAbortCompleted() {
        ConnectionHeadersFilterLayer.this.aborted.set(abortCause);
        abort(abortCause);
        try {
            next().doCloseSend();
            next().onRecvClosed(abortCause);
        } catch (IOException e) {
            // ignore
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void onRecvClosed(IOException cause) throws IOException {
        if (headerInputLength.hasRemaining() || headerInputContent.hasRemaining()) {
            // we have still not exchanged headers
            super.onRecvClosed(cause);
            return;
        }
        if (headerOutput.hasRemaining()) {
            // we have still not exchanged headers
            super.onRecvClosed(cause);
            return;
        }
        ConnectionRefusalException aborted = this.aborted.get();
        if (aborted != null && !(cause instanceof ConnectionRefusalException)) {
            // handle the case where we have refuseded the incoming headers and actually aborted
            ConnectionRefusalException newCause = newAbortCause(aborted);
            super.onRecvClosed(ThrowableUtils.chain(newCause, cause));
            return;
        }
        if (abortCause != null) {
            // handle the case where waiting on acknowledgement of the abort
            ConnectionRefusalException newCause = newAbortCause(abortCause);
            super.onRecvClosed(ThrowableUtils.chain(newCause, cause));
            return;
        }
        if (cause instanceof ClosedChannelException) {
            // we are still in the stack so we have not flushed our response yet
            // the remote end has closed because it refused our end before flushing the streams
            ConnectionRefusalException newCause =
                    new ConnectionRefusalException("Remote closed connection without specifying reason");
            super.onRecvClosed(ThrowableUtils.chain(newCause, cause));
        } else {
            super.onRecvClosed(cause);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRecvOpen() {
        return aborted.get() == null && super.isRecvOpen();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void doSend(@NonNull ByteBuffer data) throws IOException {
        ConnectionRefusalException aborted = this.aborted.get();
        if (aborted != null) {
            throw newAbortCause(aborted);
        }
        synchronized (this) {
            if (this.headerOutput.hasRemaining()) {
                // flush any headers we haven't sent yet as the other side is expecting them.
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "[{0}] Sending {1} bytes of headers", new Object[] {
                        stack().name(), this.headerOutput.remaining()
                    });
                }
                next().doSend(this.headerOutput);
                if (!this.headerOutput.hasRemaining() && LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "[{0}] Headers sent", stack().name());
                }
            }
            if (this.responseOutput != null && this.responseOutput.hasRemaining()) {
                // flush any response we haven't sent yet as the other side is expecting them.
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "[{0}] Sending {1} bytes of response", new Object[] {
                        stack().name(), this.responseOutput.remaining()
                    });
                }
                next().doSend(this.responseOutput);
                if (!this.responseOutput.hasRemaining() && LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "[{0}] Response sent", stack().name());
                }
            }
            if (abortCause != null) {
                // throw on the floor
                ((Buffer) data).clear();
            } else if (finished) {
                // we can just send through
                if (sendQueue.hasRemaining()) {
                    sendQueue.put(data);
                    flushSend(sendQueue);
                } else if (next() != null) {
                    next().doSend(data);
                    if (!sendQueue.hasRemaining() && !recvQueue.hasRemaining()) {
                        complete();
                    }
                }
            } else {
                // buffer until we are done
                sendQueue.put(data);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSendOpen() {
        return aborted.get() == null && super.isSendOpen();
    }

    /**
     * A listener for the connection headers.
     */
    public interface Listener {
        /**
         * Validate the supplied connection headers from the remote end.
         *
         * @param headers the remote headers
         * @throws ConnectionRefusalException if the remote headers are refuseded.
         */
        void onReceiveHeaders(Map<String, String> headers) throws ConnectionRefusalException;
    }

    /**
     * A task to abort the connection if no acknowledgement of refusal within the handshaking timeout
     */
    private class Aborter implements Runnable {
        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            onAbortCompleted();
        }
    }
}
