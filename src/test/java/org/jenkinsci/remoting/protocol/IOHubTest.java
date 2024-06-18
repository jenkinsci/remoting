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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.io.IOUtils;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

public class IOHubTest {

    @Rule
    public IOHubRule hub = new IOHubRule();

    @Test
    @IOHubRule.Skip
    public void noHub() {
        assertThat(hub.executorService(), nullValue());
        assertThat(hub.hub(), nullValue());
    }

    @Test
    @IOHubRule.Skip("foo")
    public void hubForDifferentId() {
        assertThat(hub.executorService(), notNullValue());
        assertThat(hub.hub(), notNullValue());
    }

    @Test
    public void hubCanRunTasks() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        hub.hub().execute(() -> {
            started.countDown();
            try {
                finish.await();
            } catch (InterruptedException e) {
                // ignore
            } finally {
                finished.countDown();
            }
        });
        assertThat(finished.getCount(), is(1L));
        started.await();
        assertThat(finished.getCount(), is(1L));
        finish.countDown();
        assertThat(finished.await(1, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void canAcceptSocketConnections() throws Exception {
        final ServerSocketChannel srv = ServerSocketChannel.open();
        srv.bind(new InetSocketAddress(0));
        srv.configureBlocking(false);
        final AtomicReference<SelectionKey> key = new AtomicReference<>();
        final AtomicBoolean oops = new AtomicBoolean(false);
        IOHub h = hub.hub();
        h.register(
                srv,
                new IOHubReadyListener() {

                    final AtomicInteger count = new AtomicInteger(0);

                    @Override
                    public void ready(boolean accept, boolean connect, boolean read, boolean write) {
                        if (accept) {
                            try {
                                SocketChannel channel = srv.accept();
                                channel.write(ByteBuffer.wrap(String.format("Go away #%d", count.incrementAndGet())
                                        .getBytes(StandardCharsets.UTF_8)));
                                channel.close();
                            } catch (IOException e) {
                                // ignore
                            }
                            h.addInterestAccept(key.get());
                        } else {
                            oops.set(true);
                        }
                        if (connect || read || write) {
                            oops.set(true);
                        }
                    }
                },
                true,
                false,
                false,
                false,
                new IOHubRegistrationCallback() {
                    @Override
                    public void onRegistered(SelectionKey selectionKey) {
                        key.set(selectionKey);
                    }

                    @Override
                    public void onClosedChannel(ClosedChannelException e) {}
                });
        Socket client = new Socket();
        client.connect(srv.getLocalAddress(), 100);
        assertThat(IOUtils.toString(client.getInputStream(), StandardCharsets.UTF_8), is("Go away #1"));
        client.close();
        client = new Socket();
        client.connect(srv.getLocalAddress(), 100);
        assertThat(IOUtils.toString(client.getInputStream(), StandardCharsets.UTF_8), is("Go away #2"));
        assertThat("Only ever called ready with accept true", oops.get(), is(false));
        client.close();
    }

    @Test
    public void afterReadyInterestIsCleared() throws Exception {
        final ServerSocketChannel srv = ServerSocketChannel.open();
        srv.bind(new InetSocketAddress(0));
        srv.configureBlocking(false);
        final AtomicReference<SelectionKey> key = new AtomicReference<>();
        final AtomicBoolean oops = new AtomicBoolean(false);
        hub.hub()
                .register(
                        srv,
                        new IOHubReadyListener() {

                            final AtomicInteger count = new AtomicInteger(0);

                            @Override
                            public void ready(boolean accept, boolean connect, boolean read, boolean write) {
                                if (accept) {
                                    try {
                                        SocketChannel channel = srv.accept();
                                        channel.write(
                                                ByteBuffer.wrap(String.format("Go away #%d", count.incrementAndGet())
                                                        .getBytes(StandardCharsets.UTF_8)));
                                        channel.close();
                                    } catch (IOException e) {
                                        // ignore
                                    }
                                } else {
                                    oops.set(true);
                                }
                                if (connect || read || write) {
                                    oops.set(true);
                                }
                            }
                        },
                        true,
                        false,
                        false,
                        false,
                        new IOHubRegistrationCallback() {
                            @Override
                            public void onRegistered(SelectionKey selectionKey) {
                                key.set(selectionKey);
                            }

                            @Override
                            public void onClosedChannel(ClosedChannelException e) {}
                        });
        try (Socket client = new Socket()) {
            client.setSoTimeout(100);
            client.connect(srv.getLocalAddress(), 100);
            assertThat(IOUtils.toString(client.getInputStream(), StandardCharsets.UTF_8), is("Go away #1"));
        }
        try (Socket client = new Socket()) {
            client.setSoTimeout(100);
            client.connect(srv.getLocalAddress(), 100);

            final SocketTimeoutException e = assertThrows(
                    SocketTimeoutException.class,
                    () -> assertThat(
                            IOUtils.toString(client.getInputStream(), StandardCharsets.UTF_8), is("Go away #2")));
            assertThat(e.getMessage(), containsString("timed out"));

            hub.hub().addInterestAccept(key.get());
            assertThat(IOUtils.toString(client.getInputStream(), StandardCharsets.UTF_8), is("Go away #2"));
            assertThat("Only ever called ready with accept true", oops.get(), is(false));
        }
    }

    @Ignore(
            "TODO flakes: Read timed out; or expected java.net.SocketTimeoutException to be thrown, but nothing was thrown")
    @Test
    public void noReadyCallbackIfInterestRemoved() throws Exception {
        final ServerSocketChannel srv = ServerSocketChannel.open();
        srv.bind(new InetSocketAddress(0));
        srv.configureBlocking(false);
        final AtomicReference<SelectionKey> key = new AtomicReference<>();
        final AtomicBoolean oops = new AtomicBoolean(false);
        IOHub h = hub.hub();
        h.register(
                srv,
                new IOHubReadyListener() {

                    final AtomicInteger count = new AtomicInteger(0);

                    @Override
                    public void ready(boolean accept, boolean connect, boolean read, boolean write) {
                        if (accept) {
                            try {
                                SocketChannel channel = srv.accept();
                                channel.write(ByteBuffer.wrap(String.format("Go away #%d", count.incrementAndGet())
                                        .getBytes(StandardCharsets.UTF_8)));
                                channel.close();
                            } catch (IOException e) {
                                // ignore
                            }
                            h.addInterestAccept(key.get());
                        } else {
                            oops.set(true);
                        }
                        if (connect || read || write) {
                            oops.set(true);
                        }
                    }
                },
                true,
                false,
                false,
                false,
                new IOHubRegistrationCallback() {
                    @Override
                    public void onRegistered(SelectionKey selectionKey) {
                        key.set(selectionKey);
                    }

                    @Override
                    public void onClosedChannel(ClosedChannelException e) {}
                });

        // Wait for registration, in other case we get unpredictable timing related results due to late registration
        while (key.get() == null) {
            Thread.sleep(10);
        }

        try (Socket client = new Socket()) {
            client.setSoTimeout(100);
            client.connect(srv.getLocalAddress(), 100);
            assertThat(IOUtils.toString(client.getInputStream(), StandardCharsets.UTF_8), is("Go away #1"));
        }
        h.removeInterestAccept(key.get());
        // wait for the interest accept to be removed
        while ((key.get().interestOps() & SelectionKey.OP_ACCEPT) != 0) {
            Thread.sleep(10);
        }
        try (Socket client = new Socket()) {
            client.setSoTimeout(100);
            client.connect(srv.getLocalAddress(), 100);

            final SocketTimeoutException e = assertThrows(
                    SocketTimeoutException.class,
                    () -> assertThat(
                            IOUtils.toString(client.getInputStream(), StandardCharsets.UTF_8), is("Go away #2")));
            assertThat(e.getMessage(), containsString("timed out"));

            h.addInterestAccept(key.get());
            assertThat(IOUtils.toString(client.getInputStream(), StandardCharsets.UTF_8), is("Go away #2"));
            assertThat("Only ever called ready with accept true", oops.get(), is(false));
        }
    }
}
