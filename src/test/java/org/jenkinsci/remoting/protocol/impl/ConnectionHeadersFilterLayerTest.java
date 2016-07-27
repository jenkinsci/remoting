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
package org.jenkinsci.remoting.protocol.impl;

import com.google.common.util.concurrent.SettableFuture;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.remoting.protocol.IOBufferMatcher;
import org.jenkinsci.remoting.protocol.IOBufferMatcherLayer;
import org.jenkinsci.remoting.protocol.IOHubRule;
import org.jenkinsci.remoting.protocol.NetworkLayerFactory;
import org.jenkinsci.remoting.protocol.ProtocolStack;
import org.jenkinsci.remoting.protocol.RepeatRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.rules.RuleChain;
import org.junit.rules.TestName;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

@RunWith(Theories.class)
public class ConnectionHeadersFilterLayerTest {

    @Rule
    public TestName name = new TestName();
    private IOHubRule selector = new IOHubRule();
    @Rule
    public RuleChain chain = RuleChain.outerRule(selector)
            .around(new RepeatRule())
            .around(new Timeout(10, TimeUnit.SECONDS));

    private Pipe clientToServer;
    private Pipe serverToClient;

    @DataPoint("blocking I/O")
    public static NetworkLayerFactory blocking() {
        return new NetworkLayerFactory.BIO();
    }

    @DataPoint("non-blocking I/O")
    public static NetworkLayerFactory nonBlocking() {
        return new NetworkLayerFactory.NIO();
    }

    @Before
    public void setUpPipe() throws Exception {
        clientToServer = Pipe.open();
        serverToClient = Pipe.open();
    }

    @After
    public void tearDownPipe() throws Exception {
        IOUtils.closeQuietly(clientToServer.sink());
        IOUtils.closeQuietly(clientToServer.source());
        IOUtils.closeQuietly(serverToClient.sink());
        IOUtils.closeQuietly(serverToClient.source());
    }

    @Theory
    public void smokes(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        ProtocolStack<IOBufferMatcher> client =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes("UTF-8");
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        server.get().send(data);
        client.get().awaitByteContent(is(expected));
        assertThat(client.get().asByteArray(), is(expected));
        server.get().close();
        client.get().awaitClose();
    }

    @Theory
    public void clientRejects(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        ProtocolStack<IOBufferMatcher> client =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new PermanentConnectionRefusalException("Go away");
                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        client.get().awaitClose();
        assertThat(client.get().getCloseCause(), instanceOf(PermanentConnectionRefusalException.class));
        server.get().awaitClose();
        assertThat(server.get().getCloseCause(), instanceOf(PermanentConnectionRefusalException.class));
    }

    @Theory
    public void serverRejects(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        ProtocolStack<IOBufferMatcher> client =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new PermanentConnectionRefusalException("Go away");
                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        client.get().awaitClose();
        assertThat(client.get().getCloseCause(), instanceOf(PermanentConnectionRefusalException.class));
        server.get().awaitClose();
        assertThat(server.get().getCloseCause(), instanceOf(PermanentConnectionRefusalException.class));
    }

    @Theory
    public void bothReject(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        ProtocolStack<IOBufferMatcher> client =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new PermanentConnectionRefusalException("Go away");
                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new PermanentConnectionRefusalException("Go away");
                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        client.get().awaitClose();
        assertThat(client.get().getCloseCause(),
                instanceOf(PermanentConnectionRefusalException.class)
        );
        server.get().awaitClose();
        assertThat(server.get().getCloseCause(),
                instanceOf(PermanentConnectionRefusalException.class)
        );
    }

    @Theory
    public void clientRefuses(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        ProtocolStack<IOBufferMatcher> client =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new ConnectionRefusalException("Go away");
                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        client.get().awaitClose();
        assertThat(client.get().getCloseCause(),
                allOf(instanceOf(ConnectionRefusalException.class), not(instanceOf(PermanentConnectionRefusalException.class))));
        server.get().awaitClose();
        assertThat(server.get().getCloseCause(),
                allOf(instanceOf(ConnectionRefusalException.class), not(instanceOf(PermanentConnectionRefusalException.class)))
        );
    }

    @Theory
    public void serverRefuses(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        ProtocolStack<IOBufferMatcher> client =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new ConnectionRefusalException("Go away");
                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        client.get().awaitClose();
        assertThat(client.get().getCloseCause(),
                allOf(instanceOf(ConnectionRefusalException.class), not(instanceOf(PermanentConnectionRefusalException.class)))
        );
        server.get().awaitClose();
        assertThat(server.get().getCloseCause(),
                allOf(instanceOf(ConnectionRefusalException.class), not(instanceOf(PermanentConnectionRefusalException.class))));
    }

    @Theory
    public void bothRefuse(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        ProtocolStack<IOBufferMatcher> client =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new ConnectionRefusalException("Go away");
                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new ConnectionHeadersFilterLayer(Collections.<String, String>emptyMap(),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new ConnectionRefusalException("Go away");
                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        client.get().awaitClose();
        assertThat(client.get().getCloseCause(),
                allOf(instanceOf(ConnectionRefusalException.class), not(instanceOf(PermanentConnectionRefusalException.class)))
        );
        server.get().awaitClose();
        assertThat(server.get().getCloseCause(),
                allOf(instanceOf(ConnectionRefusalException.class), not(instanceOf(PermanentConnectionRefusalException.class)))
        );
    }

    @Theory
    public void headerExchange(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        Random entropy = new Random();
        final SettableFuture<Map<String, String>> serverActualHeaders = SettableFuture.create();
        Map<String, String> clientExpectedHeaders = new HashMap<String, String>();
        for (int i = 1 + entropy.nextInt(50); i > 0; i--) {
            clientExpectedHeaders.put(Long.toHexString(entropy.nextLong()), Long.toHexString(entropy.nextLong()));
        }
        ProtocolStack<IOBufferMatcher> client =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new ConnectionHeadersFilterLayer(clientExpectedHeaders,
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        serverActualHeaders.set(headers);
                                    }
                                }))
                        .build(new IOBufferMatcherLayer());

        final SettableFuture<Map<String, String>> clientActualHeaders = SettableFuture.create();
        Map<String, String> serverExpectedHeaders = new HashMap<String, String>();
        for (int i = 1 + entropy.nextInt(50); i > 0; i--) {
            serverExpectedHeaders.put(Long.toHexString(entropy.nextLong()), Long.toHexString(entropy.nextLong()));
        }
        ProtocolStack<IOBufferMatcher> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new ConnectionHeadersFilterLayer(serverExpectedHeaders,
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        clientActualHeaders.set(headers);
                                    }
                                }))
                        .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes("UTF-8");
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        server.get().send(data);
        client.get().awaitByteContent(is(expected));
        assertThat(client.get().asByteArray(), is(expected));

        assertThat(serverActualHeaders.get(1000, TimeUnit.MICROSECONDS), is(serverExpectedHeaders));
        assertThat(clientActualHeaders.get(1000, TimeUnit.MICROSECONDS), is(clientExpectedHeaders));
    }

    @Theory
    public void tooBigHeader(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        final SettableFuture<Map<String, String>> serverActualHeaders = SettableFuture.create();
        Map<String, String> clientExpectedHeaders = new HashMap<String, String>(64);
        StringBuilder bigString = new StringBuilder(8*128);
        for (int i = 0; i < 128; i++) {
            bigString.append("Too Big!");
        }
        for (int i = 0; i < 64; i++) {
            clientExpectedHeaders.put(String.format("key-%d", i), bigString.toString());
        }
        try {
            ProtocolStack
                    .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                    .filter(new ConnectionHeadersFilterLayer(clientExpectedHeaders,
                            new ConnectionHeadersFilterLayer.Listener() {
                                @Override
                                public void onReceiveHeaders(Map<String, String> headers)
                                        throws ConnectionRefusalException {
                                    serverActualHeaders.set(headers);
                                }
                            }))
                    .build(new IOBufferMatcherLayer());
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("less than 65536"));
        }
    }

}
