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
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

import hudson.remoting.Callable;
import hudson.remoting.Channel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.Pipe;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import org.jenkinsci.remoting.RoleChecker;
import org.jenkinsci.remoting.protocol.cert.RSAKeyPairRule;
import org.jenkinsci.remoting.protocol.cert.SSLContextRule;
import org.jenkinsci.remoting.protocol.cert.X509CertificateRule;
import org.jenkinsci.remoting.protocol.impl.AckFilterLayer;
import org.jenkinsci.remoting.protocol.impl.BIONetworkLayer;
import org.jenkinsci.remoting.protocol.impl.ChannelApplicationLayer;
import org.jenkinsci.remoting.protocol.impl.ConnectionHeadersFilterLayer;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;
import org.jenkinsci.remoting.protocol.impl.HoldFilterLayer;
import org.jenkinsci.remoting.protocol.impl.NIONetworkLayer;
import org.jenkinsci.remoting.protocol.impl.SSLEngineFilterLayer;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestName;
import org.junit.rules.Timeout;

public class ProtocolStackImplTest {

    @Rule
    public IOHubRule selector = new IOHubRule();

    @Rule
    public TestName name = new TestName();

    private static RSAKeyPairRule keys = new RSAKeyPairRule();
    private static X509CertificateRule certificate = X509CertificateRule.selfSigned(keys);
    private static SSLContextRule context =
            new SSLContextRule().as(keys, certificate).trusting(certificate);

    @ClassRule
    public static RuleChain staticCtx =
            RuleChain.outerRule(keys).around(certificate).around(context);

    @Rule
    public RuleChain ctx = RuleChain.outerRule(new RepeatRule()).around(new Timeout(60, TimeUnit.SECONDS));

    @Test
    public void basicReadthrough() throws Exception {
        Pipe input = Pipe.open();
        Pipe output = Pipe.open();

        ProtocolStack<IOBufferMatcher> instance = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), input.source(), output.sink()))
                .build(new IOBufferMatcherLayer());
        byte[] expected = "Here is some sample data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        input.sink().write(data);
        input.sink().close();
        instance.get().awaitByteContent(is(expected));
        assertThat(instance.get().asByteArray(), is(expected));
        instance.get().awaitClose();
        assertThat(instance.get().asByteArray(), is(expected));
    }

    @Test
    public void basicWritethrough() throws Exception {
        Pipe input = Pipe.open();
        Pipe output = Pipe.open();

        ProtocolStack<IOBufferMatcher> instance = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), input.source(), output.sink()))
                .build(new IOBufferMatcherLayer());
        byte[] expected = "Here is some sample data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        instance.get().send(data);
        data.clear();
        while (data.hasRemaining()) {
            if (output.source().read(data) == -1) {
                break;
            }
        }
        assertThat(data.remaining(), is(0));
        data.flip();
        byte[] actual = new byte[expected.length];
        data.get(actual);
        assertThat(actual, is(expected));
    }

    /**
     * The behaviour of a {@link Pipe} with regard to propagation of the close is counter-intuitive. It doesn't
     * propagate the close status to the other side, rather if the {@link Pipe#source()} is closed, the
     * {@link Pipe#sink()} will just throw an {@link IOException} with {@literal Broken pipe} as the message.
     */
    @Test
    public void pipeCloseSource() throws Exception {
        Pipe pipe = Pipe.open();
        assertThat(pipe.source().isOpen(), is(true));
        assertThat(pipe.sink().isOpen(), is(true));
        pipe.source().close();
        assertThat(pipe.source().isOpen(), is(false));
        assertThat(pipe.sink().isOpen(), is(true));
        try {
            pipe.sink().write(ByteBuffer.allocate(1));
            // TODO failing here would make sense, but the condition is reached on Windows
        } catch (IOException e) {
            assertThat(e.getMessage(), containsString("Broken pipe"));
        }
        assertThat("No detection of source closed", pipe.sink().isOpen(), is(true));
        Thread.sleep(1000);
        assertThat("No detection of source closed", pipe.sink().isOpen(), is(true));
    }

    /**
     * The behaviour of a {@link Pipe} with regard to propagation of the close is counter-intuitive. It doesn't
     * propagate the close status to the other side, rather if the {@link Pipe#sink()} is closed, the
     * {@link Pipe#source()} will just return {@literal -1} from the {@link Pipe.SourceChannel#read(ByteBuffer)}.
     */
    @Test
    public void pipeCloseSink() throws Exception {
        Pipe pipe = Pipe.open();
        assertThat(pipe.source().isOpen(), is(true));
        assertThat(pipe.sink().isOpen(), is(true));
        pipe.sink().close();
        assertThat(pipe.sink().isOpen(), is(false));
        assertThat(pipe.source().isOpen(), is(true));
        ByteBuffer buffer = ByteBuffer.allocate(1);
        assertThat(pipe.source().read(buffer), is(-1));
        assertThat("No data read", buffer.remaining(), is(1));
        assertThat("No detection of sink closed", pipe.source().isOpen(), is(true));
    }

    /**
     * The behaviour of a {@link Pipe} with regard to propagation of the close is counter-intuitive. It doesn't
     * propagate the close status to the other side, rather if the {@link Pipe#sink()} is closed, the
     * {@link Pipe#source()} will just return {@literal -1} from the {@link Pipe.SourceChannel#read(ByteBuffer)} once
     * all the buffered data has been read.
     */
    @Test
    public void pipeCloseSinkAfterWrite() throws Exception {
        Pipe pipe = Pipe.open();
        assertThat(pipe.source().isOpen(), is(true));
        assertThat(pipe.sink().isOpen(), is(true));
        pipe.sink().write(ByteBuffer.allocate(1));
        pipe.sink().close();
        assertThat(pipe.sink().isOpen(), is(false));
        assertThat(pipe.source().isOpen(), is(true));
        ByteBuffer buffer = ByteBuffer.allocate(2);
        assertThat(pipe.source().read(buffer), is(1));
        assertThat("Output data read", buffer.remaining(), is(1));
        assertThat(pipe.source().read(buffer), is(-1));
        assertThat("No data read", buffer.remaining(), is(1));
        assertThat("No detection of sink closed", pipe.source().isOpen(), is(true));
    }

    @Test
    public void pipeBasicBackToBack() throws Exception {
        Pipe eastToWest = Pipe.open();
        Pipe westToEast = Pipe.open();
        ProtocolStack<IOBufferMatcher> east = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> west = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                .build(new IOBufferMatcherLayer());
        byte[] expected = "Here is some sample data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close(null);
        east.get().awaitClose();
    }

    @Test
    public void socketBasicBackToBack() throws Exception {
        ServerSocketChannel eastServer = ServerSocketChannel.open();
        eastServer.socket().bind(new InetSocketAddress(0));
        SocketChannel westChannel = SocketChannel.open();
        westChannel.connect(eastServer.getLocalAddress());
        SocketChannel eastChannel = eastServer.accept();
        ProtocolStack<IOBufferMatcher> east = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> west = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), westChannel, westChannel))
                .build(new IOBufferMatcherLayer());
        byte[] expected = "Here is some sample data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close(null);
        east.get().awaitClose();
    }

    @Test
    public void pipeBasicBackToBackWithAck() throws Exception {
        Pipe eastToWest = Pipe.open();
        Pipe westToEast = Pipe.open();
        ProtocolStack<IOBufferMatcher> east = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                .filter(new AckFilterLayer())
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> west = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                .filter(new AckFilterLayer())
                .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close(null);
        east.get().awaitClose();
    }

    @Test
    public void socketBasicBackToBackWithAck() throws Exception {
        ServerSocketChannel eastServer = ServerSocketChannel.open();
        eastServer.socket().bind(new InetSocketAddress(0));
        SocketChannel westChannel = SocketChannel.open();
        westChannel.connect(eastServer.getLocalAddress());
        SocketChannel eastChannel = eastServer.accept();
        ProtocolStack<IOBufferMatcher> east = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                .filter(new AckFilterLayer())
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> west = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), westChannel, westChannel))
                .filter(new AckFilterLayer())
                .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close(null);
        east.get().awaitClose();
    }

    @Test
    public void pipeBasicBackToBackWithAckSSLEngine() throws Exception {
        Pipe eastToWest = Pipe.open();
        Pipe westToEast = Pipe.open();
        SSLEngine westEngine = context.createSSLEngine();
        westEngine.setUseClientMode(false);
        westEngine.setNeedClientAuth(true);
        SSLEngine eastEngine = context.createSSLEngine();
        eastEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> east = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(eastEngine, null))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> west = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(westEngine, null))
                .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close(null);
        east.get().awaitClose();
    }

    @Test
    public void socketBasicBackToBackWithAckSSLEngine() throws Exception {
        ServerSocketChannel eastServer = ServerSocketChannel.open();
        eastServer.socket().bind(new InetSocketAddress(0));
        SocketChannel westChannel = SocketChannel.open();
        westChannel.connect(eastServer.getLocalAddress());
        SocketChannel eastChannel = eastServer.accept();
        SSLEngine westEngine = context.createSSLEngine();
        westEngine.setUseClientMode(false);
        westEngine.setNeedClientAuth(true);
        SSLEngine eastEngine = context.createSSLEngine();
        eastEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> east = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(eastEngine, null))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> west = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), westChannel, westChannel))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(westEngine, null))
                .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close(null);
        east.get().awaitClose();
    }

    @Test
    public void pipeBasicBackToBackWithAckSSLEngineHeaders() throws Exception {
        Pipe eastToWest = Pipe.open();
        Pipe westToEast = Pipe.open();
        SSLEngine westEngine = context.createSSLEngine();
        westEngine.setUseClientMode(false);
        westEngine.setNeedClientAuth(true);
        SSLEngine eastEngine = context.createSSLEngine();
        eastEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> east = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(eastEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "east"), headers -> {}))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> west = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(westEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "west"), headers -> {}))
                .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close(null);
        east.get().awaitClose();
    }

    @Test
    public void socketBasicBackToBackWithAckSSLEngineHeaders() throws Exception {
        ServerSocketChannel eastServer = ServerSocketChannel.open();
        eastServer.socket().bind(new InetSocketAddress(0));
        SocketChannel westChannel = SocketChannel.open();
        westChannel.connect(eastServer.getLocalAddress());
        SocketChannel eastChannel = eastServer.accept();
        SSLEngine westEngine = context.createSSLEngine();
        westEngine.setUseClientMode(false);
        westEngine.setNeedClientAuth(true);
        SSLEngine eastEngine = context.createSSLEngine();
        eastEngine.setUseClientMode(true);

        ProtocolStack<IOBufferMatcher> east = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(eastEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "east"), headers -> {}))
                .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> west = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), westChannel, westChannel))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(westEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "west"), headers -> {}))
                .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes(StandardCharsets.UTF_8);
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close(null);
        east.get().awaitClose();
    }

    @Test
    public void pipeChannelFullProtocolBIO() throws Exception {
        Pipe eastToWest = Pipe.open();
        Pipe westToEast = Pipe.open();
        SSLEngine westEngine = context.createSSLEngine();
        westEngine.setUseClientMode(false);
        westEngine.setNeedClientAuth(true);
        SSLEngine eastEngine = context.createSSLEngine();
        eastEngine.setUseClientMode(true);

        ProtocolStack<Future<Channel>> east = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(eastEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "east"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> west = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(westEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "west"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));
        east.get().get().call(new ProbeCallable());
        west.get().get().call(new ProbeCallable());
        west.get().get().close();
        east.get().get().close();
    }

    @Test
    public void socketChannelFullProtocolBIO() throws Exception {
        ServerSocketChannel eastServer = ServerSocketChannel.open();
        eastServer.socket().bind(new InetSocketAddress(0));
        SocketChannel westChannel = SocketChannel.open();
        westChannel.connect(eastServer.getLocalAddress());
        SocketChannel eastChannel = eastServer.accept();
        SSLEngine westEngine = context.createSSLEngine();
        westEngine.setUseClientMode(false);
        westEngine.setNeedClientAuth(true);
        SSLEngine eastEngine = context.createSSLEngine();
        eastEngine.setUseClientMode(true);

        ProtocolStack<Future<Channel>> east = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                .named("east")
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(eastEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "east"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> west = ProtocolStack.on(
                        new BIONetworkLayer(selector.hub(), westChannel, westChannel))
                .named("west")
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(westEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "west"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));
        east.get().get().call(new ProbeCallable());
        west.get().get().call(new ProbeCallable());
        west.get().get().close();
        east.get().get().close();
    }

    @Test
    public void pipeFullProtocolNIO() throws Exception {
        Pipe eastToWest = Pipe.open();
        Pipe westToEast = Pipe.open();
        SSLEngine westEngine = context.createSSLEngine();
        westEngine.setUseClientMode(false);
        westEngine.setNeedClientAuth(true);
        SSLEngine eastEngine = context.createSSLEngine();
        eastEngine.setUseClientMode(true);

        ProtocolStack<Future<Channel>> east = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(eastEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "east"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> west = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(westEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "west"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));
        east.get().get().call(new ProbeCallable());
        west.get().get().call(new ProbeCallable());
        west.get().get().close();
        east.get().get().close();
    }

    @Test
    public void socketChannelFullProtocolNIO() throws Exception {
        ServerSocketChannel eastServer = ServerSocketChannel.open();
        eastServer.socket().bind(new InetSocketAddress(0));
        SocketChannel westChannel = SocketChannel.open();
        westChannel.connect(eastServer.getLocalAddress());
        SocketChannel eastChannel = eastServer.accept();
        SSLEngine westEngine = context.createSSLEngine();
        westEngine.setUseClientMode(false);
        westEngine.setNeedClientAuth(true);
        SSLEngine eastEngine = context.createSSLEngine();
        eastEngine.setUseClientMode(true);

        ProtocolStack<Future<Channel>> east = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(eastEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "east"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> west = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), westChannel, westChannel))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(westEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "west"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));
        east.get().get().call(new ProbeCallable());
        west.get().get().call(new ProbeCallable());
        west.get().get().close();
        east.get().get().close();
    }

    @Test
    @Repeat(16)
    public void pipeChannelFullProtocolNIO_clientRejects() throws Exception {
        Pipe clientToServer = Pipe.open();
        Pipe serverToClient = Pipe.open();
        SSLEngine serverEngine = context.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = context.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<Future<Channel>> client = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "client"), headers -> {
                    throw new ConnectionRefusalException("I don't like you, Mr. Server");
                }))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> server = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "server"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        final ExecutionException ce =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> client.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(ce.getCause(), instanceOf(ConnectionRefusalException.class));

        final ExecutionException se =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> server.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(
                se.getCause(),
                anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(ClosedChannelException.class)));
    }

    @Test
    @Repeat(16)
    public void socketChannelFullProtocolNIO_clientRejects() throws Exception {
        ServerSocketChannel serverServerSocketChannel = ServerSocketChannel.open();
        serverServerSocketChannel.socket().bind(new InetSocketAddress(0));
        SocketChannel clientSocketChannel = SocketChannel.open();
        clientSocketChannel.connect(serverServerSocketChannel.getLocalAddress());
        SocketChannel serverSocketChannel = serverServerSocketChannel.accept();
        SSLEngine serverEngine = context.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = context.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<Future<Channel>> client = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), clientSocketChannel, clientSocketChannel))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "client"), headers -> {
                    throw new ConnectionRefusalException("I don't like you, Mr. Server");
                }))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> server = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), serverSocketChannel, serverSocketChannel))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "server"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        final ExecutionException ce =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> client.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(ce.getCause(), instanceOf(ConnectionRefusalException.class));

        final ExecutionException se =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> server.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(
                se.getCause(),
                anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(ClosedChannelException.class)));
    }

    @Test
    @Repeat(16)
    public void pipeChannelFullProtocolNIO_serverRejects() throws Exception {
        Pipe clientToServer = Pipe.open();
        Pipe serverToClient = Pipe.open();
        SSLEngine serverEngine = context.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = context.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<Future<Channel>> client = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "client"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> server = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "server"), headers -> {
                    throw new ConnectionRefusalException("I don't like you, Mr. Server");
                }))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        final ExecutionException ce =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> client.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(
                ce.getCause(),
                anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(ClosedChannelException.class)));

        final ExecutionException se =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> server.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(se.getCause(), instanceOf(ConnectionRefusalException.class));
    }

    @Test
    @Repeat(16)
    public void socketChannelFullProtocolNIO_serverRejects() throws Exception {
        ServerSocketChannel serverServerSocketChannel = ServerSocketChannel.open();
        serverServerSocketChannel.socket().bind(new InetSocketAddress(0));
        SocketChannel clientSocketChannel = SocketChannel.open();
        clientSocketChannel.connect(serverServerSocketChannel.getLocalAddress());
        SocketChannel serverSocketChannel = serverServerSocketChannel.accept();
        SSLEngine serverEngine = context.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = context.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<Future<Channel>> client = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), clientSocketChannel, clientSocketChannel))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "client"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> server = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), serverSocketChannel, serverSocketChannel))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "server"), headers -> {
                    throw new ConnectionRefusalException("I don't like you, Mr. Client");
                }))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        final ExecutionException ce =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> client.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(
                ce.getCause(),
                anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(ClosedChannelException.class)));

        final ExecutionException se =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> server.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(se.getCause(), instanceOf(ConnectionRefusalException.class));
    }

    @Ignore("TODO flake: ConnectionRefusal Incorrect acknowledgement received, expected 0x000341436b got 0x0000000000")
    @Test
    @Repeat(16)
    public void pipeChannelFullProtocolNIO_invalidAck() throws Exception {
        Pipe clientToServer = Pipe.open();
        Pipe serverToClient = Pipe.open();
        SSLEngine serverEngine = context.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = context.createSSLEngine();
        clientEngine.setUseClientMode(true);

        HoldFilterLayer clientHold = new HoldFilterLayer();
        ProtocolStack<Future<Channel>> client = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), serverToClient.source(), clientToServer.sink()))
                .filter(clientHold)
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "client"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        HoldFilterLayer serverHold = new HoldFilterLayer();
        ProtocolStack<Future<Channel>> server = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), clientToServer.source(), serverToClient.sink()))
                .filter(serverHold)
                .filter(new AckFilterLayer("ACk"))
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "server"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));
        clientHold.release();
        serverHold.release();

        final ExecutionException ce =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> client.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(
                ce.getCause(),
                anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(ClosedChannelException.class)));

        final ExecutionException se =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> server.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(
                se.getCause(),
                anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(ClosedChannelException.class)));
    }

    @Ignore(
            "TODO flake: ConnectionRefusalException: Incorrect acknowledgement received, expected 0x000341436b got 0x0000000000")
    @Test
    @Repeat(16)
    public void socketChannelFullProtocolNIO_invalidAck() throws Exception {
        ServerSocketChannel serverServerSocketChannel = ServerSocketChannel.open();
        serverServerSocketChannel.socket().bind(new InetSocketAddress(0));
        SocketChannel clientSocketChannel = SocketChannel.open();
        clientSocketChannel.connect(serverServerSocketChannel.getLocalAddress());
        SocketChannel serverSocketChannel = serverServerSocketChannel.accept();
        SSLEngine serverEngine = context.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = context.createSSLEngine();
        clientEngine.setUseClientMode(true);

        HoldFilterLayer clientHold = new HoldFilterLayer();
        ProtocolStack<Future<Channel>> client = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), clientSocketChannel, clientSocketChannel))
                .filter(clientHold)
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(clientEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "client"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));

        HoldFilterLayer serverHold = new HoldFilterLayer();
        ProtocolStack<Future<Channel>> server = ProtocolStack.on(
                        new NIONetworkLayer(selector.hub(), serverSocketChannel, serverSocketChannel))
                .filter(serverHold)
                .filter(new AckFilterLayer("ACk"))
                .filter(new SSLEngineFilterLayer(serverEngine, null))
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "server"), headers -> {}))
                .build(new ChannelApplicationLayer(selector.executorService(), null));
        clientHold.release();
        serverHold.release();

        final ExecutionException ce =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> client.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(
                ce.getCause(),
                anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(ClosedChannelException.class)));

        final ExecutionException se =
                assertThrows("Expected Connection refusal", ExecutionException.class, () -> server.get()
                        .get()
                        .call(new ProbeCallable()));
        assertThat(
                se.getCause(),
                anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(ClosedChannelException.class)));
    }

    private static class ProbeCallable implements Callable<String, IOException> {
        @Override
        public String call() throws IOException {
            System.out.println("Hello from: " + getChannelOrFail());
            return null;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {}

        private static final long serialVersionUID = 1L;
    }
}
