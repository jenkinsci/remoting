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

import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
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
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.rules.RuleChain;
import org.junit.rules.TestName;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

@RunWith(Theories.class)
public class NetworkLayerTest {

    @Rule
    public TestName name = new TestName();
    private IOHubRule selector = new IOHubRule();
    @Rule
    public RuleChain chain = RuleChain.outerRule(selector)
            .around(new RepeatRule())
            .around(new Timeout(10, TimeUnit.MINUTES));

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
        List<BatchSendBufferingFilterLayer> result = new ArrayList<BatchSendBufferingFilterLayer>();
        if (Boolean.getBoolean("fullTests")) {
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
        return result.toArray(new BatchSendBufferingFilterLayer[result.size()]);
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
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
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
    public void doCloseRecv(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory) throws Exception {
        Logger.getAnonymousLogger().log(Level.INFO, "serverFactory: {0} clientFactory: {1}",
                new Object[]{serverFactory.getClass().getSimpleName(), clientFactory.getClass().getSimpleName()});
        ProtocolStack<IOBufferMatcher> client =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes("UTF-8");
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        server.get().send(data);
        client.get().awaitByteContent(is(expected));
        assertThat(client.get().asByteArray(), is(expected));
        server.get().closeRead();
        client.get().awaitClose();
    }

    @Theory
    @Repeat(value = 1024, stopAfter = 1)
    public void concurrentStress_1_1(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 1, 1);
    }

    @Theory
    @Repeat(value = 1024, stopAfter = 1)
    public void concurrentStress_64_1(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 64, 1);
    }

    @Theory
    @Repeat(value = 1024, stopAfter = 1)
    public void concurrentStress_1_64(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 1, 64);
    }

    @Theory
    @Repeat(value = 1024, stopAfter = 1)
    public void concurrentStress_1k_1k(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 1024, 1024);
    }

    @Theory
    @Repeat(value = 1024, stopAfter = 1)
    public void concurrentStress_1k_64k(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 1024, 65536);
    }

    @Theory
    @Repeat(value = 1024, stopAfter = 1)
    public void concurrentStress_64k_1k(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 65536, 1024);
    }

    @Theory
    @Repeat(value = 1024, stopAfter = 1)
    public void concurrentStress_64k_64k(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 65536, 65536);
    }

    @Theory
    @Repeat(value = 16, stopAfter = 1)
    public void concurrentStress_1m_2m(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 1024 * 1024, 2048 * 1024);
    }

    @Theory
    @Repeat(value = 16, stopAfter = 1)
    public void concurrentStress_2m_1m(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory)
            throws Exception {
        concurrentStress(serverFactory, clientFactory, 2048 * 1024, 1024 * 1024);
    }

    private void concurrentStress(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory, int serverLimit,
                                  int clientLimit)
            throws java.io.IOException, InterruptedException, java.util.concurrent.ExecutionException {
        Logger.getLogger(name.getMethodName()).log(
                Level.INFO, "Starting test with server {0} client {1}",
                new Object[]{serverFactory.getClass().getSimpleName(), clientFactory.getClass().getSimpleName()});
        ProtocolStack<IOBufferMatcher> clientStack =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> serverStack =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .build(new IOBufferMatcherLayer());

        final IOBufferMatcher client = clientStack.get();
        final IOBufferMatcher server = serverStack.get();
        Future<Void> clientWork = selector.executorService().submit(new SequentialSender(client, clientLimit, 11));
        Future<Void> serverWork = selector.executorService().submit(new SequentialSender(server, serverLimit, 13));

        clientWork.get();
        serverWork.get();

        client.awaitByteContent(SequentialSender.matcher(serverLimit));
        server.awaitByteContent(SequentialSender.matcher(clientLimit));

        client.close();
        server.close();

        client.awaitClose();
        server.awaitClose();

        assertThat(client.asByteArray(), SequentialSender.matcher(serverLimit));
        assertThat(server.asByteArray(), SequentialSender.matcher(clientLimit));
    }

    @Theory
    public void sendingBiggerAndBiggerBatches(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory,
                                              BatchSendBufferingFilterLayer batch)
            throws java.io.IOException, InterruptedException, java.util.concurrent.ExecutionException {
        Logger.getLogger(name.getMethodName()).log(
                Level.INFO, "Starting test with server {0} client {1} batch {2}", new Object[]{
                        serverFactory.getClass().getSimpleName(),
                        clientFactory.getClass().getSimpleName(),
                        batch
                });
        ProtocolStack<IOBufferMatcher> clientStack =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> serverStack =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(batch)
                        .build(new IOBufferMatcherLayer());

        final IOBufferMatcher client = clientStack.get();
        final IOBufferMatcher server = serverStack.get();
        Future<Void> serverWork = selector.executorService().submit(new SequentialSender(server, 65536 * 4, 13));

        serverWork.get();
        batch.flush();

        client.awaitByteContent(SequentialSender.matcher(65536 * 4), 5, TimeUnit.SECONDS);

        client.awaitByteContent(SequentialSender.matcher(65536 * 4));

        client.close();
        server.close();

        client.awaitClose();
        server.awaitClose();

        assertThat(client.asByteArray(), SequentialSender.matcher(65536 * 4));
    }

    @Theory
    public void bidiSendingBiggerAndBiggerBatches(NetworkLayerFactory serverFactory, NetworkLayerFactory clientFactory,
                                                  BatchSendBufferingFilterLayer batch)
            throws java.io.IOException, InterruptedException, java.util.concurrent.ExecutionException {
        Logger.getLogger(name.getMethodName()).log(
                Level.INFO, "Starting test with server {0} client {1} batch {2}", new Object[]{
                        serverFactory.getClass().getSimpleName(),
                        clientFactory.getClass().getSimpleName(),
                        batch
                });
        BatchSendBufferingFilterLayer clientBatch = batch.clone();
        ProtocolStack<IOBufferMatcher> clientStack =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new NoOpFilterLayer())
                        .filter(clientBatch)
                        .filter(new NoOpFilterLayer())
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> serverStack =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new NoOpFilterLayer())
                        .filter(batch)
                        .filter(new NoOpFilterLayer())
                        .build(new IOBufferMatcherLayer());

        final IOBufferMatcher client = clientStack.get();
        final IOBufferMatcher server = serverStack.get();
        Future<Void> clientWork = selector.executorService().submit(new SequentialSender(client, 65536 * 4, 11));
        Future<Void> serverWork = selector.executorService().submit(new SequentialSender(server, 65536 * 4, 13));

        clientWork.get();
        serverWork.get();
        clientBatch.flush();
        batch.flush();

        client.awaitByteContent(SequentialSender.matcher(65536 * 4));
        server.awaitByteContent(SequentialSender.matcher(65536 * 4));
    }

}
