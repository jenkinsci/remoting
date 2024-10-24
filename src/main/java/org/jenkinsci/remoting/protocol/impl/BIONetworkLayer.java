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
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.NetworkLayer;
import org.jenkinsci.remoting.protocol.ProtocolStack;
import org.jenkinsci.remoting.util.ByteBufferQueue;
import org.jenkinsci.remoting.util.IOUtils;

/**
 * A {@link NetworkLayer} that uses a dedicated reader thread and runs on demand writer thread to manage I/O.
 *
 * @since 3.0
 */
public class BIONetworkLayer extends NetworkLayer {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(BIONetworkLayer.class.getName());
    /**
     * The source of data, may be the same as {@link #out}, for example in the case of a {@link SocketChannel}.
     */
    private final ReadableByteChannel in;
    /**
     * The sink for data, may be the same as {@link #in}, for example in the case of a {@link SocketChannel}.
     */
    private final WritableByteChannel out;
    /**
     * Our reader task, stays running until {@link #in} is closed.
     */
    private final Reader reader = new Reader();
    /**
     * Our writer task, each run will stop once all the data in {@link #writeQueue} has been written.
     */
    private final Writer writer = new Writer();
    /**
     * The queue of data to be written.
     */
    private final ByteBufferQueue writeQueue = new ByteBufferQueue(8192);
    /**
     * Boolean flag to mark the {@link #reader} as having been submitted for execution.
     */
    private boolean starting;
    /**
     * Boolean flag to mark the {@link #reader} as currently running.
     */
    private boolean running;

    /**
     * Constructor.
     *
     * @param ioHub our hub.
     * @param in    the source of data.
     * @param out   the sink for data.
     */
    public BIONetworkLayer(IOHub ioHub, ReadableByteChannel in, WritableByteChannel out) {
        super(ioHub);
        this.in = in;
        this.out = out;
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
            // no-op return immediately
            return;
        }
        if (!out.isOpen()) {
            throw new ClosedChannelException();
        }
        synchronized (writeQueue) {
            if (!writeQueue.hasRemaining()) {
                // if the writer is running, it will bail as when it last held the lock it detected a drain
                // thus we need to start a new writer. The writer will not be able to make progress until we
                // release the lock, so it's safe to start it as the write queue will be non-empty when we leave
                // here and any subsequent calls to write() before our submitted writer starts will detect remaining
                getIoHub().execute(writer);
            }
            writeQueue.put(data);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void doCloseRecv() {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] Closing RECV", stack().name());
        }
        if (in.isOpen()) {
            IOUtils.closeQuietly(in);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void start() throws IOException {
        if (starting || running) {
            return;
        }
        if (!getIoHub().isOpen()) {
            throw new IllegalStateException("IOHub must be open: " + getIoHub());
        }
        starting = true;
        getIoHub().execute(reader);
        super.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void doCloseSend() throws IOException {
        if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "[{0}] Closing SEND", stack().name());
        }
        if (out.isOpen()) {
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
     * The task for writing all the data in {@link BIONetworkLayer#sendQueue} to {@link BIONetworkLayer#out}
     */
    private class Writer implements Runnable {

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void run() {
            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.log(Level.FINEST, "[{0}] Writer thread started", stack().name());
            }
            try {
                ByteBuffer data = acquire();
                try {
                    boolean done = false;
                    while (getIoHub().isOpen() && out.isOpen() && !done) {
                        ((Buffer) data).clear();
                        synchronized (writeQueue) {
                            writeQueue.get(data);
                            done = !writeQueue.hasRemaining();
                        }
                        ((Buffer) data).flip();
                        while (data.remaining() > 0) {
                            try {
                                if (out.write(data) == -1) {
                                    doCloseSend();
                                    return;
                                }
                            } catch (IOException e) {
                                try {
                                    doCloseSend();
                                    return;
                                } catch (IOException e1) {
                                    // ignore
                                }
                            }
                        }
                    }
                } finally {
                    release(data);
                }
            } finally {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "[{0}] Writer thread stopped", stack().name());
                }
            }
        }
    }

    /**
     * The task for reading from {@link BIONetworkLayer#in}
     */
    private class Reader implements Runnable {

        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            LOGGER.info("Waiting for ProtocolStack to start.");
            try {
                ProtocolStack.waitForStart();
            } catch (InterruptedException e) {
                LOGGER.warning("Interrupted while waiting for ProtocolStack to start.");
                return;
            }
            synchronized (BIONetworkLayer.this) {
                starting = false;
                running = true;
            }
            try {
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "[{0}] Reader thread started", stack().name());
                }
                ByteBuffer buffer = acquire();
                try {
                    while (getIoHub().isOpen() && in.isOpen() && isRecvOpen()) {
                        try {
                            int read = in.read(buffer);
                            if (read < 0) {
                                onRecvClosed();
                                return;
                            }
                        } catch (SocketTimeoutException e) {
                            // perfectly normal, loop back around and check everything is still open before retrying
                            continue;
                        } catch (ClosedChannelException e) {
                            onRecvClosed();
                            return;
                        } catch (IOException e) {
                            if (LOGGER.isLoggable(Level.FINER) && !(e instanceof EOFException)) {
                                // will likely be reported elsewhere, so we just trace this at FINER
                                LogRecord record = new LogRecord(Level.FINER, "[{0}] Unexpected I/O exception");
                                record.setThrown(e);
                                record.setParameters(new Object[] {stack().name()});
                                LOGGER.log(record);
                            }
                            onRecvClosed();
                            return;
                        } catch (RuntimeException e) {
                            // this should *never* happen... but just in case it does we will log & close connection
                            if (LOGGER.isLoggable(Level.WARNING)) {
                                LogRecord record = new LogRecord(Level.WARNING, "[{0}] Uncaught {1}");
                                record.setThrown(e);
                                record.setParameters(new Object[] {
                                    stack().name(), e.getClass().getSimpleName()
                                });
                            }
                            onRecvClosed();
                            return;
                        }
                        ((Buffer) buffer).flip();
                        if (buffer.hasRemaining() && LOGGER.isLoggable(Level.FINEST)) {
                            LOGGER.log(Level.FINEST, "[{0}] RECV: {1} bytes", new Object[] {
                                stack().name(), buffer.remaining()
                            });
                        }
                        while (buffer.hasRemaining()) {
                            try {
                                onRead(buffer);
                            } catch (IOException e) {
                                onRecvClosed();
                                return;
                            }
                        }
                        ((Buffer) buffer).clear();
                    }
                } finally {
                    release(buffer);
                }
            } catch (Throwable e) {
                if (LOGGER.isLoggable(Level.SEVERE)) {
                    LogRecord record = new LogRecord(Level.SEVERE, "[{0}] Reader thread killed by {1}");
                    record.setThrown(e);
                    record.setParameters(
                            new Object[] {stack().name(), e.getClass().getSimpleName()});
                    LOGGER.log(record);
                }
                if (e instanceof Error) {
                    throw (Error) e;
                }
            } finally {
                synchronized (BIONetworkLayer.this) {
                    running = false;
                }
                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.log(Level.FINEST, "[{0}] Reader thread stopped", stack().name());
                }
            }
        }
    }
}
