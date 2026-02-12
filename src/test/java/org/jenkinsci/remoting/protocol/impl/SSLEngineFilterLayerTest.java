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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.remoting.protocol.IOBufferMatcher;
import org.jenkinsci.remoting.protocol.IOBufferMatcherLayer;
import org.jenkinsci.remoting.protocol.IOHubExtension;
import org.jenkinsci.remoting.protocol.NetworkLayerFactory;
import org.jenkinsci.remoting.protocol.ProtocolStack;
import org.jenkinsci.remoting.protocol.cert.RSAKeyPairExtension;
import org.jenkinsci.remoting.protocol.cert.SSLContextExtension;
import org.jenkinsci.remoting.protocol.cert.X509CertificateExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@ParameterizedClass
@MethodSource("parameters")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SSLEngineFilterLayerTest {

    private static final boolean FULL_TESTS = Boolean.getBoolean("fullTests");

    @Order(0)
    @RegisterExtension
    private static final RSAKeyPairExtension CA_ROOT_KEY = new RSAKeyPairExtension();

    @Order(1)
    @RegisterExtension
    private static final RSAKeyPairExtension CLIENT_KEY = new RSAKeyPairExtension();

    @Order(2)
    @RegisterExtension
    private static final RSAKeyPairExtension SERVER_KEY = new RSAKeyPairExtension();

    @Order(3)
    @RegisterExtension
    private static final X509CertificateExtension CA_ROOT_CERT =
            X509CertificateExtension.create("caRoot", CA_ROOT_KEY, CA_ROOT_KEY, null);

    @Order(4)
    @RegisterExtension
    private static final X509CertificateExtension CLIENT_CERT =
            X509CertificateExtension.create("client", CLIENT_KEY, CA_ROOT_KEY, CA_ROOT_CERT);

    @Order(5)
    @RegisterExtension
    private static final X509CertificateExtension SERVER_CERT =
            X509CertificateExtension.create("server", SERVER_KEY, CA_ROOT_KEY, CA_ROOT_CERT);

    @Order(6)
    @RegisterExtension
    private static final X509CertificateExtension EXPIRED_CLIENT_CERT = X509CertificateExtension.create(
            "expiredClient", CLIENT_KEY, CA_ROOT_KEY, CA_ROOT_CERT, -10, -5, TimeUnit.DAYS);

    @Order(7)
    @RegisterExtension
    private static final X509CertificateExtension NOT_YET_VALID_SERVER_CERT = X509CertificateExtension.create(
            "notYetValidServer", SERVER_KEY, CA_ROOT_KEY, CA_ROOT_CERT, +5, +10, TimeUnit.DAYS);

    @Order(8)
    @RegisterExtension
    private static final SSLContextExtension CLIENT_CTX = new SSLContextExtension("client")
            .as(CLIENT_KEY, CLIENT_CERT, CA_ROOT_CERT)
            .trusting(CA_ROOT_CERT)
            .trusting(SERVER_CERT);

    @Order(9)
    @RegisterExtension
    private static final SSLContextExtension SERVER_CTX = new SSLContextExtension("server")
            .as(SERVER_KEY, SERVER_CERT, CA_ROOT_CERT)
            .trusting(CA_ROOT_CERT)
            .trusting(CLIENT_CERT);

    @Order(10)
    @RegisterExtension
    private static final SSLContextExtension EXPIRED_CLIENT_CTX = new SSLContextExtension("expiredClient")
            .as(CLIENT_KEY, EXPIRED_CLIENT_CERT, CA_ROOT_CERT)
            .trusting(CA_ROOT_CERT)
            .trusting(SERVER_CERT);

    @Order(11)
    @RegisterExtension
    private static final SSLContextExtension NOT_YET_VALID_SERVER_CTX = new SSLContextExtension("notYetValidServer")
            .as(SERVER_KEY, NOT_YET_VALID_SERVER_CERT, CA_ROOT_CERT)
            .trusting(CA_ROOT_CERT)
            .trusting(CLIENT_CERT);

    @Order(12)
    @RegisterExtension
    private static final SSLContextExtension UNTRUSTING_CLIENT_CTX = new SSLContextExtension("untrustingClient")
            .as(CLIENT_KEY, CLIENT_CERT)
            .trusting(CA_ROOT_CERT);

    @Order(13)
    @RegisterExtension
    private static final SSLContextExtension UNTRUSTING_SERVER_CTX = new SSLContextExtension("untrustingServer")
            .as(SERVER_KEY, SERVER_CERT)
            .trusting(CA_ROOT_CERT);

    @RegisterExtension
    private final IOHubExtension selector = new IOHubExtension();

    @Parameter(0)
    private NetworkLayerFactory serverFactory;

    @Parameter(1)
    private NetworkLayerFactory clientFactory;

    private Pipe clientToServer;
    private Pipe serverToClient;

    private String name;

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
    void beforeEach(TestInfo info) throws Exception {
        name = info.getTestMethod().orElseThrow().getName();
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
        SSLEngine serverEngine = SERVER_CTX.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = CLIENT_CTX.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> client = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> server = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new SSLEngineFilterLayer(serverEngine, null))
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
    void clientRejectsServer() throws Exception {
        SSLEngine serverEngine = SERVER_CTX.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = CLIENT_CTX.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> client = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new SSLEngineFilterLayer(clientEngine, session -> {
                    throw new ConnectionRefusalException("Bad server");
                }))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> server = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .build(new IOBufferMatcherLayer());

        IOBufferMatcher clientMatcher = client.get();
        IOBufferMatcher serverMatcher = server.get();

        clientMatcher.awaitClose();
        serverMatcher.awaitClose();
        assertThat(clientMatcher.getCloseCause(), instanceOf(ConnectionRefusalException.class));
        assertThat(serverMatcher.getCloseCause(), instanceOf(ClosedChannelException.class));
    }

    @Test
    void serverRejectsClient() throws Exception {
        Logger.getLogger(name).log(Level.INFO, "Starting test with server {0} client {1}", new Object[] {
            serverFactory.getClass().getSimpleName(), clientFactory.getClass().getSimpleName(),
        });
        SSLEngine serverEngine = SERVER_CTX.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = CLIENT_CTX.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> client = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> server = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new SSLEngineFilterLayer(serverEngine, session -> {
                    throw new ConnectionRefusalException("Bad client");
                }))
                .build(new IOBufferMatcherLayer());

        IOBufferMatcher clientMatcher = client.get();
        IOBufferMatcher serverMatcher = server.get();

        Logger.getLogger(name).log(Level.INFO, "Waiting for client close");
        clientMatcher.awaitClose();
        Logger.getLogger(name).log(Level.INFO, "Waiting for server close");
        serverMatcher.awaitClose();
        assertThat(clientMatcher.getCloseCause(), instanceOf(ClosedChannelException.class));
        assertThat(serverMatcher.getCloseCause(), instanceOf(ConnectionRefusalException.class));
        Logger.getLogger(name).log(Level.INFO, "Done");
    }

    @Test
    void untrustingClientDoesNotConnect() throws Exception {
        SSLEngine serverEngine = SERVER_CTX.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = UNTRUSTING_CLIENT_CTX.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> client = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> server = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .build(new IOBufferMatcherLayer());

        IOBufferMatcher clientMatcher = client.get();
        IOBufferMatcher serverMatcher = server.get();

        clientMatcher.awaitClose();
        serverMatcher.awaitClose();
        assertThat(clientMatcher.getCloseCause(), instanceOf(SSLHandshakeException.class));
        assertThat(serverMatcher.getCloseCause(), instanceOf(ClosedChannelException.class));
    }

    @Test
    void expiredClientDoesNotConnect() throws Exception {
        SSLEngine serverEngine = SERVER_CTX.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = EXPIRED_CLIENT_CTX.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> client = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> server = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .build(new IOBufferMatcherLayer());

        IOBufferMatcher clientMatcher = client.get();
        IOBufferMatcher serverMatcher = server.get();

        clientMatcher.awaitClose();
        serverMatcher.awaitClose();
        assertThat(clientMatcher.getCloseCause(), instanceOf(ClosedChannelException.class));
        assertThat(serverMatcher.getCloseCause(), instanceOf(SSLHandshakeException.class));
    }

    @Test
    void clientDoesNotConnectToNotYetValidServer() throws Exception {
        SSLEngine serverEngine = NOT_YET_VALID_SERVER_CTX.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = EXPIRED_CLIENT_CTX.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> client = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> server = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .build(new IOBufferMatcherLayer());

        IOBufferMatcher clientMatcher = client.get();
        IOBufferMatcher serverMatcher = server.get();

        clientMatcher.awaitClose();
        serverMatcher.awaitClose();
        assertThat(clientMatcher.getCloseCause(), instanceOf(SSLHandshakeException.class));
        assertThat(serverMatcher.getCloseCause(), instanceOf(ClosedChannelException.class));
    }

    @RepeatedTest(value = 16, failureThreshold = 1)
    void concurrentStress_1_1() throws Exception {
        concurrentStress(serverFactory, clientFactory, 1, 1);
    }

    @RepeatedTest(value = 16, failureThreshold = 1)
    void concurrentStress_512_512() throws Exception {
        concurrentStress(serverFactory, clientFactory, 512, 512);
    }

    @RepeatedTest(value = 16, failureThreshold = 1)
    void concurrentStress_1k_1k() throws Exception {
        concurrentStress(serverFactory, clientFactory, 1024, 1024);
    }

    @RepeatedTest(value = 16, failureThreshold = 1)
    void concurrentStress_2k_1k() throws Exception {
        concurrentStress(serverFactory, clientFactory, 2048, 1024);
    }

    @RepeatedTest(value = 16, failureThreshold = 1)
    void concurrentStress_1k_2k() throws Exception {
        concurrentStress(serverFactory, clientFactory, 1024, 2048);
    }

    @RepeatedTest(value = 16, failureThreshold = 1)
    void concurrentStress_2k_2k() throws Exception {
        concurrentStress(serverFactory, clientFactory, 2048, 2048);
    }

    @Test
    void concurrentStress_4k_4k_minus_1() throws Exception {
        concurrentStress(serverFactory, clientFactory, 4095, 4095);
    }

    @Test
    void concurrentStress_4k_4k() throws Exception {
        concurrentStress(serverFactory, clientFactory, 4096, 4096);
    }

    @Test
    void concurrentStress_4k_4k_plus_1() throws Exception {
        concurrentStress(serverFactory, clientFactory, 4097, 4097);
    }

    @Test
    void concurrentStress_16k_16k_minus_1() throws Exception {
        concurrentStress(serverFactory, clientFactory, 16383, 16383);
    }

    @Test
    void concurrentStress_16k_16k() throws Exception {
        concurrentStress(serverFactory, clientFactory, 16384, 16384);
    }

    @Test
    void concurrentStress_16k_16k_plus_1() throws Exception {
        concurrentStress(serverFactory, clientFactory, 16385, 16385);
    }

    @Test
    void concurrentStress_64k_64k() throws Exception {
        concurrentStress(serverFactory, clientFactory, 65536, 65536);
    }

    private void concurrentStress(
            NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory, int serverLimit, int clientLimit)
            throws IOException, InterruptedException, ExecutionException {
        Logger.getLogger(name)
                .log(
                        Level.INFO,
                        "Starting test with server {0} client {1} serverLimit {2} clientLimit {3}",
                        new Object[] {
                            serverFactory.getClass().getSimpleName(),
                            clientFactory.getClass().getSimpleName(),
                            serverLimit,
                            clientLimit
                        });
        SSLEngine serverEngine = SERVER_CTX.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = CLIENT_CTX.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> clientStack = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> serverStack = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .build(new IOBufferMatcherLayer());

        final IOBufferMatcher client = clientStack.get();
        final IOBufferMatcher server = serverStack.get();
        Future<Void> clientWork = selector.executorService().submit(new SequentialSender(client, clientLimit, 11));
        Future<Void> serverWork = selector.executorService().submit(new SequentialSender(server, serverLimit, 13));

        clientWork.get();
        serverWork.get();

        client.awaitByteContent(SequentialSender.matcher(serverLimit));
        server.awaitByteContent(SequentialSender.matcher(clientLimit));

        client.close(null);
        server.close(null);

        client.awaitClose();
        server.awaitClose();

        assertThat(client.asByteArray(), SequentialSender.matcher(serverLimit));
        assertThat(server.asByteArray(), SequentialSender.matcher(clientLimit));
    }

    static List<BatchSendBufferingFilterLayer> batches() {
        List<BatchSendBufferingFilterLayer> result = new ArrayList<>();
        if (FULL_TESTS) {
            int length = 16;
            while (length < 65536) {
                result.add(new BatchSendBufferingFilterLayer(length));
                if (length < 16) {
                    length = length * 2;
                } else {
                    length = length * 3 / 2;
                }
            }
        } else {
            result.add(new BatchSendBufferingFilterLayer(16));
            result.add(new BatchSendBufferingFilterLayer(4096));
            result.add(new BatchSendBufferingFilterLayer(65536));
        }
        return result;
    }

    @ParameterizedTest
    @MethodSource("batches")
    void sendingBiggerAndBiggerBatches(BatchSendBufferingFilterLayer batch)
            throws IOException, InterruptedException, ExecutionException {
        Logger.getLogger(name).log(Level.INFO, "Starting test with server {0} client {1} batch {2}", new Object[] {
            serverFactory.getClass().getSimpleName(), clientFactory.getClass().getSimpleName(), batch
        });
        SSLEngine serverEngine = SERVER_CTX.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = CLIENT_CTX.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> clientStack = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> serverStack = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .filter(batch)
                .build(new IOBufferMatcherLayer());

        final IOBufferMatcher client = clientStack.get();
        final IOBufferMatcher server = serverStack.get();
        int amount = FULL_TESTS ? 65536 * 4 : 16384;
        Future<Void> serverWork = selector.executorService().submit(new SequentialSender(server, amount, 13));

        serverWork.get();
        batch.flush();

        client.awaitByteContent(SequentialSender.matcher(amount));

        client.close(null);
        server.close(null);

        client.awaitClose();
        server.awaitClose();

        assertThat(client.asByteArray(), SequentialSender.matcher(amount));
    }

    @ParameterizedTest
    @MethodSource("batches")
    void bidiSendingBiggerAndBiggerBatches(BatchSendBufferingFilterLayer batch)
            throws IOException, InterruptedException, ExecutionException {
        Logger.getLogger(name).log(Level.INFO, "Starting test with server {0} client {1} batch {2}", new Object[] {
            serverFactory.getClass().getSimpleName(), clientFactory.getClass().getSimpleName(), batch
        });
        SSLEngine serverEngine = SERVER_CTX.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = CLIENT_CTX.createSSLEngine();
        clientEngine.setUseClientMode(true);

        BatchSendBufferingFilterLayer clientBatch = batch.clone();
        ProtocolStack<IOBufferMatcher> clientStack = ProtocolStack.on(
                        clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new NoOpFilterLayer())
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .filter(clientBatch)
                .filter(new NoOpFilterLayer())
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> serverStack = ProtocolStack.on(
                        serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new NoOpFilterLayer())
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .filter(batch)
                .filter(new NoOpFilterLayer())
                .build(new IOBufferMatcherLayer());

        final IOBufferMatcher client = clientStack.get();
        final IOBufferMatcher server = serverStack.get();
        int clientAmount = FULL_TESTS ? 65536 * 4 : 16384;
        Future<Void> clientWork = selector.executorService().submit(new SequentialSender(client, clientAmount, 11));
        int serverAmount = FULL_TESTS ? 65536 * 4 : 16384;
        Future<Void> serverWork = selector.executorService().submit(new SequentialSender(server, serverAmount, 13));

        clientWork.get();
        serverWork.get();
        clientBatch.flush();
        batch.flush();

        client.awaitByteContent(SequentialSender.matcher(clientAmount));
        server.awaitByteContent(SequentialSender.matcher(serverAmount));
    }
}
