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
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.remoting.protocol.IOBufferMatcher;
import org.jenkinsci.remoting.protocol.IOBufferMatcherLayer;
import org.jenkinsci.remoting.protocol.IOHubRule;
import org.jenkinsci.remoting.protocol.NetworkLayerFactory;
import org.jenkinsci.remoting.protocol.ProtocolStack;
import org.jenkinsci.remoting.protocol.Repeat;
import org.jenkinsci.remoting.protocol.RepeatRule;
import org.jenkinsci.remoting.protocol.cert.RSAKeyPairRule;
import org.jenkinsci.remoting.protocol.cert.SSLContextRule;
import org.jenkinsci.remoting.protocol.cert.X509CertificateRule;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.experimental.theories.DataPoint;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.rules.RuleChain;
import org.junit.rules.TestName;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class SSLEngineFilterLayerTest {

    private static final boolean fullTests = Boolean.getBoolean("fullTests");

    private static RSAKeyPairRule clientKey = new RSAKeyPairRule();
    private static RSAKeyPairRule serverKey = new RSAKeyPairRule();
    private static RSAKeyPairRule caRootKey = new RSAKeyPairRule();
    private static X509CertificateRule caRootCert = X509CertificateRule.create("caRoot", caRootKey, caRootKey, null);
    private static X509CertificateRule clientCert =
            X509CertificateRule.create("client", clientKey, caRootKey, caRootCert);
    private static X509CertificateRule serverCert =
            X509CertificateRule.create("server", serverKey, caRootKey, caRootCert);
    private static X509CertificateRule expiredClientCert =
            X509CertificateRule.create("expiredClient", clientKey, caRootKey, caRootCert, -10, -5, TimeUnit.DAYS);
    private static X509CertificateRule notYetValidServerCert =
            X509CertificateRule.create("notYetValidServer", serverKey, caRootKey, caRootCert, +5, +10, TimeUnit.DAYS);
    private static SSLContextRule clientCtx = new SSLContextRule("client")
            .as(clientKey, clientCert, caRootCert)
            .trusting(caRootCert)
            .trusting(serverCert);
    private static SSLContextRule serverCtx = new SSLContextRule("server")
            .as(serverKey, serverCert, caRootCert)
            .trusting(caRootCert)
            .trusting(clientCert);
    private static SSLContextRule expiredClientCtx = new SSLContextRule("expiredClient")
            .as(clientKey, expiredClientCert, caRootCert)
            .trusting(caRootCert)
            .trusting(serverCert);
    private static SSLContextRule notYetValidServerCtx = new SSLContextRule("notYetValidServer")
            .as(serverKey, notYetValidServerCert, caRootCert)
            .trusting(caRootCert)
            .trusting(clientCert);
    private static SSLContextRule untrustingClientCtx =
            new SSLContextRule("untrustingClient").as(clientKey, clientCert).trusting(caRootCert);
    private static SSLContextRule untrustingServerCtx =
            new SSLContextRule("untrustingServer").as(serverKey, serverCert).trusting(caRootCert);

    @ClassRule
    public static RuleChain staticCtx = RuleChain.outerRule(caRootKey)
            .around(clientKey)
            .around(serverKey)
            .around(caRootCert)
            .around(clientCert)
            .around(serverCert)
            .around(expiredClientCert)
            .around(notYetValidServerCert)
            .around(clientCtx)
            .around(serverCtx)
            .around(expiredClientCtx)
            .around(notYetValidServerCtx)
            .around(untrustingClientCtx)
            .around(untrustingServerCtx);

    @Rule
    public IOHubRule selector = new IOHubRule();

    @Rule
    public TestName name = new TestName();

    private Timeout globalTimeout = new Timeout(30, TimeUnit.SECONDS);

    @Rule
    public RuleChain ctx = RuleChain.outerRule(new RepeatRule()).around(globalTimeout);

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

    @DataPoints
    public static BatchSendBufferingFilterLayer[] batchSizes() {
        List<BatchSendBufferingFilterLayer> result = new ArrayList<>();
        if (fullTests) {
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
        return result.toArray(new BatchSendBufferingFilterLayer[0]);
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
        SSLEngine serverEngine = serverCtx.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = clientCtx.createSSLEngine();
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

    @Theory
    public void clientRejectsServer(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        SSLEngine serverEngine = serverCtx.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = clientCtx.createSSLEngine();
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

    @Theory
    public void serverRejectsClient(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        Logger.getLogger(name.getMethodName())
                .log(Level.INFO, "Starting test with server {0} client {1}", new Object[] {
                    serverFactory.getClass().getSimpleName(),
                    clientFactory.getClass().getSimpleName(),
                });
        SSLEngine serverEngine = serverCtx.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = clientCtx.createSSLEngine();
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

        Logger.getLogger(name.getMethodName()).log(Level.INFO, "Waiting for client close");
        clientMatcher.awaitClose();
        Logger.getLogger(name.getMethodName()).log(Level.INFO, "Waiting for server close");
        serverMatcher.awaitClose();
        assertThat(clientMatcher.getCloseCause(), instanceOf(ClosedChannelException.class));
        assertThat(serverMatcher.getCloseCause(), instanceOf(ConnectionRefusalException.class));
        Logger.getLogger(name.getMethodName()).log(Level.INFO, "Done");
    }

    @Theory
    public void untrustingClientDoesNotConnect(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        SSLEngine serverEngine = serverCtx.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = untrustingClientCtx.createSSLEngine();
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

    @Theory
    public void expiredClientDoesNotConnect(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        SSLEngine serverEngine = serverCtx.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = expiredClientCtx.createSSLEngine();
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

    @Theory
    public void clientDoesNotConnectToNotYetValidServer(
            NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        SSLEngine serverEngine = notYetValidServerCtx.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = expiredClientCtx.createSSLEngine();
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

    @Theory
    @Repeat(value = 16, stopAfter = 1)
    public void concurrentStress_1_1(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 1, 1);
    }

    @Theory
    @Repeat(value = 16, stopAfter = 1)
    public void concurrentStress_512_512(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 512, 512);
    }

    @Theory
    @Repeat(value = 16, stopAfter = 1)
    public void concurrentStress_1k_1k(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 1024, 1024);
    }

    @Theory
    @Repeat(value = 16, stopAfter = 1)
    public void concurrentStress_2k_1k(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 2048, 1024);
    }

    @Theory
    @Repeat(value = 16, stopAfter = 1)
    public void concurrentStress_1k_2k(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 1024, 2048);
    }

    @Theory
    @Repeat(value = 16, stopAfter = 1)
    public void concurrentStress_2k_2k(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 2048, 2048);
    }

    @Theory
    public void concurrentStress_4k_4k_minus_1(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 4095, 4095);
    }

    @Theory
    public void concurrentStress_4k_4k(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 4096, 4096);
    }

    @Theory
    public void concurrentStress_4k_4k_plus_1(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 4097, 4097);
    }

    @Theory
    public void concurrentStress_16k_16k_minus_1(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 16383, 16383);
    }

    @Theory
    public void concurrentStress_16k_16k(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 16384, 16384);
    }

    @Theory
    public void concurrentStress_16k_16k_plus_1(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 16385, 16385);
    }

    @Theory
    public void concurrentStress_64k_64k(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 65536, 65536);
    }

    private void concurrentStress(
            NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory, int serverLimit, int clientLimit)
            throws IOException, InterruptedException, ExecutionException {
        Logger.getLogger(name.getMethodName())
                .log(
                        Level.INFO,
                        "Starting test with server {0} client {1} serverLimit {2} clientLimit {3}",
                        new Object[] {
                            serverFactory.getClass().getSimpleName(),
                            clientFactory.getClass().getSimpleName(),
                            serverLimit,
                            clientLimit
                        });
        SSLEngine serverEngine = serverCtx.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = clientCtx.createSSLEngine();
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

    @Theory
    public void sendingBiggerAndBiggerBatches(
            NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory, BatchSendBufferingFilterLayer batch)
            throws IOException, InterruptedException, ExecutionException {
        Logger.getLogger(name.getMethodName())
                .log(Level.INFO, "Starting test with server {0} client {1} batch {2}", new Object[] {
                    serverFactory.getClass().getSimpleName(),
                    clientFactory.getClass().getSimpleName(),
                    batch
                });
        SSLEngine serverEngine = serverCtx.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = clientCtx.createSSLEngine();
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
        int amount = fullTests ? 65536 * 4 : 16384;
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

    @Theory
    public void bidiSendingBiggerAndBiggerBatches(
            NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory, BatchSendBufferingFilterLayer batch)
            throws IOException, InterruptedException, ExecutionException {
        Logger.getLogger(name.getMethodName())
                .log(Level.INFO, "Starting test with server {0} client {1} batch {2}", new Object[] {
                    serverFactory.getClass().getSimpleName(),
                    clientFactory.getClass().getSimpleName(),
                    batch
                });
        SSLEngine serverEngine = serverCtx.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = clientCtx.createSSLEngine();
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
        int clientAmount = fullTests ? 65536 * 4 : 16384;
        Future<Void> clientWork = selector.executorService().submit(new SequentialSender(client, clientAmount, 11));
        int serverAmount = fullTests ? 65536 * 4 : 16384;
        Future<Void> serverWork = selector.executorService().submit(new SequentialSender(server, serverAmount, 13));

        clientWork.get();
        serverWork.get();
        clientBatch.flush();
        batch.flush();

        client.awaitByteContent(SequentialSender.matcher(clientAmount));
        server.awaitByteContent(SequentialSender.matcher(serverAmount));
    }
}
