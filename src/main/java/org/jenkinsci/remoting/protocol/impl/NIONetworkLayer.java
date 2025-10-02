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
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.IOHubReadyListener;
import org.jenkinsci.remoting.protocol.IOHubRegistrationCallback;
import org.jenkinsci.remoting.protocol.NetworkLayer;
import org.jenkinsci.remoting.util.ByteBufferQueue;
import org.jenkinsci.remoting.util.IOUtils;

/**
 * A {@link NetworkLayer} that uses the NIO {@link Selector} of a {@link IOHub} to manage I/O.
 *
 * @since 3.0
 */
public class NIONetworkLayer extends NetworkLayer implements IOHubReadyListener {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(NIONetworkLayer.class.getName());
    /**
     * We use this lock to linearize writes
     */
    private final Lock sendLock = new ReentrantLock();
    /**
     * We use this lock to linearize reads
     */
    private final Lock recvLock = new ReentrantLock();
    /**
     * This queue caches any writes before we are registered with the {@link Selector}.
     */
    private final ByteBufferQueue sendQueue;
    /**
     * The source of data, may be the same as {@link #out}, for example in the case of a {@link SocketChannel}.
     */
    private final ReadableByteChannel in;
    /**
     * The sink for data, may be the same as {@link #in}, for example in the case of a {@link SocketChannel}.
     */
    private final WritableByteChannel out;
    /**
     * The {@link SelectionKey} on our {@link IOHub#getSelector()} for {@link #out}.
     */
    private SelectionKey sendKey;
    /**
     * The {@link SelectionKey} on our {@link IOHub#getSelector()} for {@link #in}.
     */
    private SelectionKey recvKey;

    /**
     * Constructor.
     *
     * @param ioHub our hub.
     * @param in    the source of data.
     * @param out   the sink for data.
     */
    public NIONetworkLayer(IOHub ioHub, ReadableByteChannel in, WritableByteChannel out) {
        super(ioHub);
        if (!(in instanceof SelectableChannel)) {
            throw new IllegalArgumentException("Input channel must be a SelectableChannel");
        } else if (((SelectableChannel) in).isBlocking()) {
            try {
                ((SelectableChannel) in).configureBlocking(false);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not configure input channel for non-blocking", e);
            }
        }
        if (!(out instanceof SelectableChannel)) {
            throw new IllegalArgumentException("Output channel must be a SelectableChannel");
        } else if (((SelectableChannel) out).isBlocking()) {
            try {
                ((SelectableChannel) out).configureBlocking(false);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not configure output channel for non-blocking", e);
            }
        }
        this.in = in;
        this.out = out;
        sendQueue = newByteBufferQueue();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void ready(boolean accept, boolean connect, boolean read, boolean write) {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "{0} - entering ready({1}, {2}, {3}, {4})", new Object[] {
                Thread.currentThread().getName(), accept, connect, read, write
            });
        }
        if (read) {
            recvLock.lock();
            try {
                if (in.isOpen()) {
                    final boolean logFinest = LOGGER.isLoggable(Level.FINEST);
                    ByteBuffer recv = acquire();
                    try {
                        READ:
                        while (true) {
                            switch (in.read(recv)) {
                                case -1:
                                    // don't cancel recvKey here, out may still be open & can share same selector key
                                    onRecvClosed();
                                    break READ;
                                case 0:
                                    // out of data
                                    if (recvKey.isValid() && in.isOpen()) {
                                        getIoHub().addInterestRead(recvKey);
                                    } else {
                                        recvKey.cancel();
                                        onRecvClosed();
                                    }
                                    break READ;
                                default:
                                    ((Buffer) recv).flip();
                                    if (logFinest) {
                                        LOGGER.log(Level.FINEST, "[{0}] RECV: {1} bytes", new Object[] {
                                            stack().name(), recv.remaining()
                                        });
                                    }
                                    while (recv.hasRemaining()) {
                                        onRead(recv);
                                    }
                                    // it's always clear when we get from acquire, so clear again for re-use
                                    ((Buffer) recv).clear();
                                    break;
                            }
                        }
                    } catch (ClosedChannelException e) {
                        recvKey.cancel();
                        onRecvClosed();
                    } catch (IOException e) {
                        if (LOGGER.isLoggable(Level.FINER)) {
                            // will be reported elsewhere, so we just trace this at FINER
                            LogRecord record = new LogRecord(Level.FINER, "[{0}] Unexpected I/O exception");
                            record.setThrown(e);
                            record.setParameters(new Object[] {stack().name()});
                            LOGGER.log(record);
                        }
                        recvKey.cancel();
                        onRecvClosed();
                    } catch (Throwable t) {
                        // this should *never* happen... but just in case it does we will log & close connection
                        try {
                            if (LOGGER.isLoggable(Level.SEVERE)) {
                                LogRecord record = new LogRecord(Level.SEVERE, "[{0}] Uncaught {1}");
                                record.setThrown(t);
                                record.setParameters(new Object[] {
                                    stack().name(), t.getClass().getSimpleName()
                                });
                                LOGGER.log(record);
                            }
                        } finally {
                            // in case this was an OOMErr and logging caused another OOMErr
                            recvKey.cancel();
                            onRecvClosed();
                        }
                    } finally {
                        release(recv);
                    }
                } else {
                    // don't cancel recvKey here, out may still be open & can share same selector key
                    onRecvClosed();
                }
            } finally {
                recvLock.unlock();
            }
        }
        if (write && out.isOpen()) {
            ByteBuffer send = acquire();
            sendLock.lock();
            try {
                boolean sendHasRemaining;
                synchronized (sendQueue) {
                    sendQueue.get(send);
                    sendHasRemaining = sendQueue.hasRemaining();
                }
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "[{0}] sendHasRemaining - has remaining: {1}", new Object[] {
                        Thread.currentThread().getName(), sendHasRemaining
                    });
                }
                ((Buffer) send).flip();
                try {
                    final int sentBytes = out.write(send);
                    if (LOGGER.isLoggable(Level.FINEST)) {
                        LOGGER.log(Level.FINEST, "[{0}] sentBytes - sent {1} bytes", new Object[] {
                            Thread.currentThread().getName(), sentBytes
                        });
                    }
                    if (sentBytes == -1) {
                        sendKey.cancel();
                        return;
                    }
                } catch (ClosedChannelException e) {
                    sendKey.cancel();
                    return;
                } catch (IOException e) {
                    if (LOGGER.isLoggable(Level.FINER)) {
                        // will be reported elsewhere, so we just trace this at FINER
                        LogRecord record = new LogRecord(Level.FINER, "[{0}] Unexpected I/O exception");
                        record.setThrown(e);
                        record.setParameters(new Object[] {stack().name()});
                        LOGGER.log(record);
                    }
                    sendKey.cancel();
                    return;
                }
                if (out.isOpen() && sendKey.isValid()) {
                    if (send.hasRemaining()) {
                        synchronized (sendQueue) {
                            this.sendQueue.unget(send);
                            sendHasRemaining = true;
                        }
                    }
                    if (sendHasRemaining) {
                        getIoHub().addInterestWrite(sendKey);
                    }
                }
            } finally {
                sendLock.unlock();
                release(send);
            }
        }
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(
                    Level.FINEST,
                    "{0} - leaving ready(...)",
                    Thread.currentThread().getName());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void write(@NonNull ByteBuffer data) throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] SEND: {1} bytes", new Object[] {stack().name(), data.remaining()});
        }
        if (!data.hasRemaining()) {
            return;
        }
        if (!out.isOpen()) {
            throw new ClosedChannelException();
        }
        boolean sendHadRemaining;
        synchronized (sendQueue) {
            sendHadRemaining = sendQueue.hasRemaining();
            sendQueue.put(data);
        }
        if (!sendHadRemaining && out.isOpen() && sendKey != null && sendKey.isValid()) {
            getIoHub().addInterestWrite(sendKey);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void doCloseRecv() {
        if (in.isOpen()) {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINEST, "[{0}] Closing RECV", stack().name());
            }
            IOUtils.closeQuietly(in);
            // as soon as we close, the SelectionKey will become invalid and thus ready will never be called
            // hence we need to notify the rest of the stack about the recvClosed()
            onRecvClosed();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws IOException {
        final boolean pendingWrite;
        synchronized (sendQueue) {
            pendingWrite = sendQueue.hasRemaining();
        }
        final SelectableChannel in = (SelectableChannel) this.in;
        final SelectableChannel out = (SelectableChannel) this.out;
        if (in == out) {
            in.configureBlocking(false);
            getIoHub()
                    .register(
                            in,
                            this,
                            false,
                            false,
                            true,
                            pendingWrite,
                            new RegistrationCallbackImpl(true, true, pendingWrite));
        } else {
            in.configureBlocking(false);
            out.configureBlocking(false);
            getIoHub()
                    .register(
                            out,
                            this,
                            false,
                            false,
                            false,
                            pendingWrite,
                            new RegistrationCallbackImpl(true, false, pendingWrite));
            getIoHub().register(in, this, false, false, true, false, new RegistrationCallbackImpl(false, true, false));
        }
        super.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void doCloseSend() {
        if (out.isOpen()) {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINEST, "[{0}] Closing SEND", stack().name());
            }
            IOUtils.closeQuietly(out);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSendOpen() {
        return out.isOpen();
    }

    /**
     * Our {@link IOHubRegistrationCallback}.
     */
    private class RegistrationCallbackImpl implements IOHubRegistrationCallback {
        /**
         * {@code true} to set the {@link NIONetworkLayer#sendKey}.
         */
        private final boolean setSendKey;
        /**
         * {@code true} to set the {@link NIONetworkLayer#recvKey}.
         */
        private final boolean setRecvKey;
        /**
         * {@code true} if there was a pending write when requesting registration with the selector.
         */
        private final boolean pendingWrite;

        /**
         * Constructor.
         *
         * @param setSendKey   {@code true} to set the {@link NIONetworkLayer#sendKey}.
         * @param setRecvKey   {@code true} to set the {@link NIONetworkLayer#recvKey}.
         * @param pendingWrite {@code true} if there was a pending write when requesting registration with the selector.
         */
        private RegistrationCallbackImpl(boolean setSendKey, boolean setRecvKey, boolean pendingWrite) {
            this.setSendKey = setSendKey;
            this.setRecvKey = setRecvKey;
            this.pendingWrite = pendingWrite;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onRegistered(SelectionKey selectionKey) {
            if (setRecvKey) {
                recvKey = selectionKey;
            }
            if (setSendKey) {
                sendLock.lock();
                try {
                    sendKey = selectionKey;
                    // check if some outgoing data has been buffered while we were waiting to register
                    if (!pendingWrite) {
                        boolean nowPendingWrite;
                        synchronized (sendQueue) {
                            nowPendingWrite = sendQueue.hasRemaining();
                        }
                        if (nowPendingWrite) {
                            sendKey.interestOps(sendKey.interestOps() | SelectionKey.OP_WRITE);
                        }
                    }
                } finally {
                    sendLock.unlock();
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onClosedChannel(ClosedChannelException e) {
            onRecvClosed();
        }
    }
}
