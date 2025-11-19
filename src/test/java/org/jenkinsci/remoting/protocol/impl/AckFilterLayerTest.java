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
import java.util.stream.Stream;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.remoting.protocol.IOBufferMatcher;
import org.jenkinsci.remoting.protocol.IOBufferMatcherLayer;
import org.jenkinsci.remoting.protocol.IOHubExtension;
import org.jenkinsci.remoting.protocol.NetworkLayerFactory;
import org.jenkinsci.remoting.protocol.ProtocolStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
@ParameterizedClass
@MethodSource("parameters")
class AckFilterLayerTest {

    @RegisterExtension
    private final IOHubExtension selector = new IOHubExtension();

    @Parameter(0)
    private NetworkLayerFactory serverFactory;

    @Parameter(1)
    private NetworkLayerFactory clientFactory;

    private Pipe clientToServer;
    private Pipe serverToClient;

    static Stream<Arguments> parameters() {
        return Stream.of(
                Arguments.of(
                        Named.of("blocking I/O", new NetworkLayerFactory.BIO()),
                        Named.of("blocking I/O", new NetworkLayerFactory.BIO())),
                Arguments.of(
                        Named.of("blocking I/O", new NetworkLayerFactory.BIO()),
                        Named.of("non-blocking I/O", new NetworkLayerFactory.NIO())),
                Arguments.of(
                        Named.of("non-blocking I/O", new NetworkLayerFactory.NIO()),
                        Named.of("blocking I/O", new NetworkLayerFactory.BIO())),
                Arguments.of(
                        Named.of("non-blocking I/O", new NetworkLayerFactory.NIO()),
                        Named.of("non-blocking I/O", new NetworkLayerFactory.NIO())));
    }

    @BeforeEach
    void beforeEach() throws Exception {
        clientToServer = Pipe.open();
        serverToClient = Pipe.open();
    }

    @AfterEach
    void afterEach() {
        IOUtils.closeQuietly(clientToServer.sink());
        IOUtils.closeQuietly(clientToServer.source());
        IOUtils.closeQuietly(serverToClient.sink());
        IOUtils.closeQuietly(serverToClient.source());
    }

    @Test
    void smokes() throws Exception {
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

    @Test
    void clientSendsShortAck() throws Exception {
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

    @RepeatedTest(100)
    void serverSendsShortAck() throws Exception {
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

    @RepeatedTest(100)
    void ackMismatch() throws Exception {
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
