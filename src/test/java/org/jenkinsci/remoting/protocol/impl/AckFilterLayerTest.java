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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.remoting.protocol.IOBufferMatcher;
import org.jenkinsci.remoting.protocol.IOBufferMatcherLayer;
import org.jenkinsci.remoting.protocol.IOHubRule;
import org.jenkinsci.remoting.protocol.NetworkLayerFactory;
import org.jenkinsci.remoting.protocol.ProtocolStack;
import org.jenkinsci.remoting.protocol.Repeat;
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

@RunWith(Theories.class)
public class AckFilterLayerTest {

    @Rule
    public TestName name = new TestName();

    private IOHubRule selector = new IOHubRule();

    @Rule
    public RuleChain chain =
            RuleChain.outerRule(selector).around(new RepeatRule()).around(new Timeout(10, TimeUnit.SECONDS));

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
    public void tearDownPipe() {
        IOUtils.closeQuietly(clientToServer.sink());
        IOUtils.closeQuietly(clientToServer.source());
        IOUtils.closeQuietly(serverToClient.sink());
        IOUtils.closeQuietly(serverToClient.source());
    }

    @Theory
    public void smokes(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        ProtocolStack<IOBufferMatcher> client = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new AckFilterLayer("ACK"))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> server = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new AckFilterLayer("ACK"))
                .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        server.get().send(data);
        client.get().awaitByteContent(is(expected));
        assertThat(client.get().asByteArray(), is(expected));
        server.get().close(null);
        client.get().awaitClose();
    }

    @Theory
    public void clientSendsShortAck(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        ProtocolStack<IOBufferMatcher> client = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new AckFilterLayer("AC"))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> server = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new AckFilterLayer("ACK"))
                .build(new IOBufferMatcherLayer());

        client.get().awaitClose();
        assertThat(client.get().getCloseCause(), instanceOf(ConnectionRefusalException.class));
        server.get().awaitClose();
        assertThat(server.get().getCloseCause(), instanceOf(ConnectionRefusalException.class));
    }

    @Theory
    @Repeat(100)
    public void serverSendsShortAck(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        ProtocolStack<IOBufferMatcher> client = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new AckFilterLayer("ACK"))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> server = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new AckFilterLayer("AC"))
                .build(new IOBufferMatcherLayer());

        client.get().awaitClose();
        assertThat(client.get().getCloseCause(), instanceOf(ConnectionRefusalException.class));
        server.get().awaitClose();
        assertThat(server.get().getCloseCause(), instanceOf(ConnectionRefusalException.class));
    }

    @Theory
    @Repeat(100)
    public void ackMismatch(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        ProtocolStack<IOBufferMatcher> client = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new AckFilterLayer("AcK"))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> server = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new AckFilterLayer("ACK"))
                .build(new IOBufferMatcherLayer());

        client.get().awaitClose();
        assertThat(client.get().getCloseCause(), instanceOf(ConnectionRefusalException.class));
        server.get().awaitClose();
        assertThat(server.get().getCloseCause(), instanceOf(ConnectionRefusalException.class));
    }
}
