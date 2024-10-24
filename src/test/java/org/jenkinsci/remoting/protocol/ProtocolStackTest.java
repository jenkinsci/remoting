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
package org.jenkinsci.remoting.protocol;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ProtocolStackTest {

    private ExecutorService executorService;
    private IOHub selector;

    @Before
    public void setUp() throws Exception {
        executorService = Executors.newCachedThreadPool();
        selector = IOHub.create(executorService);
    }

    @After
    public void tearDown() {
        IOUtils.closeQuietly(selector);
        executorService.shutdownNow();
    }

    @Test
    public void initSequence() throws IOException {
        Logger logger = Logger.getLogger(ProtocolStack.class.getName());
        CapturingHandler handler = new CapturingHandler();
        assertThat(logger.isLoggable(Level.FINEST), is(false));
        Level oldLevel = logger.getLevel();
        logger.addHandler(handler);
        try {
            logger.setLevel(Level.FINEST);
            assertThat(logger.isLoggable(Level.FINEST), is(true));
            final AtomicInteger state = new AtomicInteger();
            ProtocolStack.on(new NetworkLayer(selector) {

                        @Override
                        protected void write(@NonNull ByteBuffer data) {}

                        @Override
                        public void start() {
                            state.compareAndSet(0, 1);
                        }

                        @Override
                        public void doCloseSend() {}

                        @Override
                        public void doCloseRecv() {}

                        @Override
                        public boolean isSendOpen() {
                            return true;
                        }
                    })
                    .filter(new FilterLayer() {
                        @Override
                        public void start() {
                            state.compareAndSet(1, 2);
                        }

                        @Override
                        public void onRecv(@NonNull ByteBuffer data) {}

                        @Override
                        public void doSend(@NonNull ByteBuffer data) {}
                    })
                    .filter(new FilterLayer() {
                        @Override
                        public void start() {
                            state.compareAndSet(2, 3);
                        }

                        @Override
                        public void onRecv(@NonNull ByteBuffer data) {}

                        @Override
                        public void doSend(@NonNull ByteBuffer data) {}
                    })
                    .named("initSeq")
                    .build(new ApplicationLayer<Void>() {
                        @Override
                        public Void get() {
                            return null;
                        }

                        @Override
                        public void onRead(@NonNull ByteBuffer data) {}

                        @Override
                        public void start() {
                            state.compareAndSet(3, 4);
                        }

                        @Override
                        public void onReadClosed(IOException cause) {}

                        @Override
                        public boolean isReadOpen() {
                            return true;
                        }
                    });
            assertThat("Init in sequence", state.get(), is(4));
            assertThat(
                    handler.logRecords,
                    contains(
                            allOf(
                                    hasProperty("message", is("[{0}] Initializing")),
                                    hasProperty("parameters", is(new Object[] {"initSeq"}))),
                            allOf(
                                    hasProperty("message", is("[{0}] Starting")),
                                    hasProperty("parameters", is(new Object[] {"initSeq"}))),
                            allOf(
                                    hasProperty("message", is("[{0}] Started")),
                                    hasProperty("parameters", is(new Object[] {"initSeq"})))));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(oldLevel);
        }
    }

    @Test
    public void initSequenceFailure() {
        Logger logger = Logger.getLogger(ProtocolStack.class.getName());
        CapturingHandler handler = new CapturingHandler();
        assertThat(logger.isLoggable(Level.FINEST), is(false));
        Level oldLevel = logger.getLevel();
        logger.addHandler(handler);
        try {
            logger.setLevel(Level.FINEST);
            assertThat(logger.isLoggable(Level.FINEST), is(true));
            final AtomicInteger state = new AtomicInteger();
            final IOException e = assertThrows(IOException.class, () -> ProtocolStack.on(new NetworkLayer(selector) {

                        @Override
                        protected void write(@NonNull ByteBuffer data) {}

                        @Override
                        public void start() {
                            state.compareAndSet(0, 1);
                        }

                        @Override
                        public void doCloseSend() {}

                        @Override
                        public void doCloseRecv() {}

                        @Override
                        public boolean isSendOpen() {
                            return true;
                        }
                    })
                    .filter(new FilterLayer() {
                        @Override
                        public void start() throws IOException {
                            state.compareAndSet(1, 2);
                            throw new IOException("boom");
                        }

                        @Override
                        public void onRecv(@NonNull ByteBuffer data) {}

                        @Override
                        public void doSend(@NonNull ByteBuffer data) {}
                    })
                    .filter(new FilterLayer() {
                        @Override
                        public void start() {
                            state.set(-2);
                        }

                        @Override
                        public void onRecv(@NonNull ByteBuffer data) {}

                        @Override
                        public void doSend(@NonNull ByteBuffer data) {}

                        @Override
                        public void onRecvClosed(IOException cause) throws IOException {
                            state.compareAndSet(2, 3);
                            super.onRecvClosed(cause);
                        }
                    })
                    .named("initSeq")
                    .build(new ApplicationLayer<Void>() {
                        @Override
                        public Void get() {
                            return null;
                        }

                        @Override
                        public void onRead(@NonNull ByteBuffer data) {}

                        @Override
                        public void start() {
                            state.set(-3);
                        }

                        @Override
                        public void onReadClosed(IOException cause) {
                            state.compareAndSet(3, 4);
                        }

                        @Override
                        public boolean isReadOpen() {
                            return true;
                        }
                    }));
            assertThat(e.getMessage(), is("boom"));

            assertThat(
                    handler.logRecords,
                    contains(
                            allOf(
                                    hasProperty("message", is("[{0}] Initializing")),
                                    hasProperty("parameters", is(new Object[] {"initSeq"}))),
                            allOf(
                                    hasProperty("message", is("[{0}] Starting")),
                                    hasProperty("parameters", is(new Object[] {"initSeq"}))),
                            allOf(
                                    hasProperty("message", is("[{0}] Start failure")),
                                    hasProperty("parameters", is(new Object[] {"initSeq"})),
                                    hasProperty("thrown", hasProperty("message", is("boom"))))));
            assertThat("Init in sequence", state.get(), is(4));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(oldLevel);
        }
    }

    @Test
    public void stackCloseSequence() throws IOException {
        Logger logger = Logger.getLogger(ProtocolStack.class.getName());
        CapturingHandler handler = new CapturingHandler();
        assertThat(logger.isLoggable(Level.FINEST), is(false));
        Level oldLevel = logger.getLevel();
        logger.addHandler(handler);
        try {
            logger.setLevel(Level.FINEST);
            assertThat(logger.isLoggable(Level.FINEST), is(true));

            final AtomicInteger state = new AtomicInteger();
            ProtocolStack.on(new NetworkLayer(selector) {

                        @Override
                        public void start() {}

                        @Override
                        protected void write(@NonNull ByteBuffer data) {}

                        @Override
                        public void doCloseRecv() {
                            state.compareAndSet(3, 4);
                            onRecvClosed();
                        }

                        @Override
                        public void doCloseSend() {
                            state.compareAndSet(2, 3);
                            doCloseRecv();
                        }

                        @Override
                        public boolean isSendOpen() {
                            return true;
                        }
                    })
                    .filter(new FilterLayer() {
                        @Override
                        public void start() {}

                        @Override
                        public void onRecv(@NonNull ByteBuffer data) {}

                        @Override
                        public void doSend(@NonNull ByteBuffer data) {}

                        @Override
                        public void doCloseSend() throws IOException {
                            state.compareAndSet(1, 2);
                            super.doCloseSend();
                        }

                        @Override
                        public void onRecvClosed(IOException cause) throws IOException {
                            state.compareAndSet(4, 5);
                            super.onRecvClosed(cause);
                        }
                    })
                    .filter(new FilterLayer() {
                        @Override
                        public void start() {}

                        @Override
                        public void onRecv(@NonNull ByteBuffer data) {}

                        @Override
                        public void doSend(@NonNull ByteBuffer data) {}

                        @Override
                        public void doCloseSend() throws IOException {
                            state.compareAndSet(0, 1);
                            super.doCloseSend();
                        }

                        @Override
                        public void onRecvClosed(IOException cause) throws IOException {
                            state.compareAndSet(5, 6);
                            super.onRecvClosed(cause);
                        }
                    })
                    .named("closeSeq")
                    .build(new ApplicationLayer<Void>() {
                        @Override
                        public boolean isReadOpen() {
                            return true;
                        }

                        @Override
                        public void onRead(@NonNull ByteBuffer data) {}

                        @Override
                        public Void get() {
                            return null;
                        }

                        @Override
                        public void start() {}

                        @Override
                        public void onReadClosed(IOException cause) {
                            state.compareAndSet(6, 7);
                        }
                    })
                    .close();
            assertThat("Close in sequence", state.get(), is(7));
            assertThat(
                    handler.logRecords,
                    contains(
                            allOf(
                                    hasProperty("message", is("[{0}] Initializing")),
                                    hasProperty("parameters", is(new Object[] {"closeSeq"}))),
                            allOf(
                                    hasProperty("message", is("[{0}] Starting")),
                                    hasProperty("parameters", is(new Object[] {"closeSeq"}))),
                            allOf(
                                    hasProperty("message", is("[{0}] Started")),
                                    hasProperty("parameters", is(new Object[] {"closeSeq"}))),
                            allOf(
                                    hasProperty("message", is("[{0}] Closing")),
                                    hasProperty("parameters", is(new Object[] {"closeSeq"}))),
                            allOf(
                                    hasProperty("message", is("[{0}] Closed")),
                                    hasProperty("parameters", is(new Object[] {"closeSeq"})))));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(oldLevel);
        }
    }

    private static class CapturingHandler extends Handler {
        private final Queue<LogRecord> logRecords = new ConcurrentLinkedQueue<>();

        @Override
        public boolean isLoggable(LogRecord record) {
            return true;
        }

        @Override
        public void publish(LogRecord record) {
            logRecords.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() throws SecurityException {}
    }
}
