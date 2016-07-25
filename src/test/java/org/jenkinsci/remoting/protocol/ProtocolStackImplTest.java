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

import hudson.remoting.Callable;
import java.io.OutputStream;
import java.nio.channels.ClosedChannelException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
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
import hudson.remoting.Channel;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Pipe;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;
import org.jenkinsci.remoting.protocol.impl.SSLEngineFilterLayer;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.theories.Theory;
import org.junit.rules.RuleChain;
import org.junit.rules.TestName;
import org.junit.rules.Timeout;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class ProtocolStackImplTest {

    @Rule
    public IOHubRule selector = new IOHubRule();
    @Rule
    public TestName name = new TestName();
    private static RSAKeyPairRule keys = new RSAKeyPairRule();
    private static X509CertificateRule certificate = X509CertificateRule.selfSigned(keys);
    private static SSLContextRule context = new SSLContextRule().as(keys, certificate).trusting(certificate);
    @ClassRule
    public static RuleChain staticCtx = RuleChain.outerRule(keys)
            .around(certificate)
            .around(context);
    @Rule
    public RuleChain ctx = RuleChain.outerRule(new RepeatRule())
            .around(new Timeout(60, TimeUnit.SECONDS));

    @Test
    public void basicReadthrough() throws Exception {
        Pipe input = Pipe.open();
        Pipe output = Pipe.open();

        ProtocolStack<IOBufferMatcher> instance =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), input.source(), output.sink()))
                        .build(new IOBufferMatcherLayer());
        byte[] expected = "Here is some sample data".getBytes("UTF-8");
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

        ProtocolStack<IOBufferMatcher> instance =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), input.source(), output.sink()))
                        .build(new IOBufferMatcherLayer());
        byte[] expected = "Here is some sample data".getBytes("UTF-8");
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
        ProtocolStack<IOBufferMatcher> east =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                        .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> west =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                        .build(new IOBufferMatcherLayer());
        byte[] expected = "Here is some sample data".getBytes("UTF-8");
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close();
        east.get().awaitClose();
    }

    @Test
    public void socketBasicBackToBack() throws Exception {
        ServerSocketChannel eastServer = ServerSocketChannel.open();
        eastServer.socket().bind(new InetSocketAddress(0));
        SocketChannel westChannel = SocketChannel.open();
        westChannel.connect(eastServer.getLocalAddress());
        SocketChannel eastChannel = eastServer.accept();
        ProtocolStack<IOBufferMatcher> east =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                        .build(new IOBufferMatcherLayer());

        ProtocolStack<IOBufferMatcher> west =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), westChannel, westChannel))
                        .build(new IOBufferMatcherLayer());
        byte[] expected = "Here is some sample data".getBytes("UTF-8");
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close();
        east.get().awaitClose();
    }

    @Test
    public void pipeBasicBackToBackWithAck() throws Exception {
        Pipe eastToWest = Pipe.open();
        Pipe westToEast = Pipe.open();
        ProtocolStack<IOBufferMatcher> east =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                        .filter(new AckFilterLayer())
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> west =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                        .filter(new AckFilterLayer())
                        .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes("UTF-8");
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close();
        east.get().awaitClose();
    }

    @Test
    public void socketBasicBackToBackWithAck() throws Exception {
        ServerSocketChannel eastServer = ServerSocketChannel.open();
        eastServer.socket().bind(new InetSocketAddress(0));
        SocketChannel westChannel = SocketChannel.open();
        westChannel.connect(eastServer.getLocalAddress());
        SocketChannel eastChannel = eastServer.accept();
        ProtocolStack<IOBufferMatcher> east =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                        .filter(new AckFilterLayer())
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> west =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), westChannel, westChannel))
                        .filter(new AckFilterLayer())
                        .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes("UTF-8");
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close();
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

        ProtocolStack<IOBufferMatcher> east =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(eastEngine, null))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> west =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(westEngine, null))
                        .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes("UTF-8");
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close();
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

        ProtocolStack<IOBufferMatcher> east =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(eastEngine, null))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> west =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), westChannel, westChannel))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(westEngine, null))
                        .build(new IOBufferMatcherLayer());

        byte[] expected = "Here is some sample data".getBytes("UTF-8");
        ByteBuffer data = ByteBuffer.allocate(expected.length);
        data.put(expected);
        data.flip();
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close();
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

        ProtocolStack<IOBufferMatcher> east =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(eastEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "east"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> west =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(westEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "west"),
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
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close();
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

        ProtocolStack<IOBufferMatcher> east =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(eastEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "east"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new IOBufferMatcherLayer());


        ProtocolStack<IOBufferMatcher> west =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), westChannel, westChannel))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(westEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "west"),
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
        west.get().send(data);
        east.get().awaitByteContent(is(expected));
        assertThat(east.get().asByteArray(), is(expected));
        west.get().close();
        east.get().awaitClose();
    }

    @Test
    @Repeat(16)
    public void pipeChannelFullProtocolBIO() throws Exception {
        Pipe eastToWest = Pipe.open();
        Pipe westToEast = Pipe.open();
        SSLEngine westEngine = context.createSSLEngine();
        westEngine.setUseClientMode(false);
        westEngine.setNeedClientAuth(true);
        SSLEngine eastEngine = context.createSSLEngine();
        eastEngine.setUseClientMode(true);

        ProtocolStack<Future<Channel>> east =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(eastEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "east"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> west =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(westEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "west"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));
        east.get().get().call(new ProbeCallable());
        west.get().get().call(new ProbeCallable());
        west.get().get().close();
        east.get().get().close();
    }

    @Test
    @Repeat(16)
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

        ProtocolStack<Future<Channel>> east =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                        .named("east")
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(eastEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "east"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> west =
                ProtocolStack.on(new BIONetworkLayer(selector.hub(), westChannel, westChannel))
                        .named("west")
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(westEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "west"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));
        east.get().get().call(new ProbeCallable());
        west.get().get().call(new ProbeCallable());
        west.get().get().close();
        east.get().get().close();
    }

    @Test
    @Repeat(16)
    public void pipeFullProtocolNIO() throws Exception {
        Pipe eastToWest = Pipe.open();
        Pipe westToEast = Pipe.open();
        SSLEngine westEngine = context.createSSLEngine();
        westEngine.setUseClientMode(false);
        westEngine.setNeedClientAuth(true);
        SSLEngine eastEngine = context.createSSLEngine();
        eastEngine.setUseClientMode(true);

        ProtocolStack<Future<Channel>> east =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), westToEast.source(), eastToWest.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(eastEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "east"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> west =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), eastToWest.source(), westToEast.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(westEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "west"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));
        east.get().get().call(new ProbeCallable());
        west.get().get().call(new ProbeCallable());
        west.get().get().close();
        east.get().get().close();

    }

    @Test
    @Repeat(16)
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

        ProtocolStack<Future<Channel>> east =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), eastChannel, eastChannel))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(eastEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "east"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> west =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), westChannel, westChannel))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(westEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "west"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
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

        ProtocolStack<Future<Channel>> client =
                ProtocolStack
                        .on(new NIONetworkLayer(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(clientEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "client"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new ConnectionRefusalException("I don't like you, Mr. Server");
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> server =
                ProtocolStack
                        .on(new NIONetworkLayer(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(serverEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "server"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));
        try {
            client.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
        try {
            server.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(
                    ClosedChannelException.class)));
        }
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

        ProtocolStack<Future<Channel>> client =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), clientSocketChannel, clientSocketChannel))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(clientEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "client"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new ConnectionRefusalException("I don't like you, Mr. Server");
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> server =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), serverSocketChannel, serverSocketChannel))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(serverEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "server"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {

                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));
        try {
            client.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
        try {
            server.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(
                    ClosedChannelException.class)));
        }
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

        ProtocolStack<Future<Channel>> client =
                ProtocolStack
                        .on(new NIONetworkLayer(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(clientEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "client"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> server =
                ProtocolStack
                        .on(new NIONetworkLayer(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(serverEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "server"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new ConnectionRefusalException("I don't like you, Mr. Server");
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));
        try {
            client.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(
                    ClosedChannelException.class)));
        }
        try {
            server.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
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

        ProtocolStack<Future<Channel>> client =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), clientSocketChannel, clientSocketChannel))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(clientEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "client"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> server =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), serverSocketChannel, serverSocketChannel))
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(serverEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "server"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                        throw new ConnectionRefusalException("I don't like you, Mr. Client");
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));
        try {
            client.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(
                    ClosedChannelException.class)));
        }
        try {
            server.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
    }

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
        ProtocolStack<Future<Channel>> client =
                ProtocolStack
                        .on(new NIONetworkLayer(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(clientHold)
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(clientEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "client"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        HoldFilterLayer serverHold = new HoldFilterLayer();
        ProtocolStack<Future<Channel>> server =
                ProtocolStack
                        .on(new NIONetworkLayer(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(serverHold)
                        .filter(new AckFilterLayer("ACk"))
                        .filter(new SSLEngineFilterLayer(serverEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "server"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));
        clientHold.release();
        serverHold.release();
        try {
            client.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(
                    ClosedChannelException.class)));
        }
        try {
            server.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(
                    ClosedChannelException.class)));
        }
    }

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
        ProtocolStack<Future<Channel>> client =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), clientSocketChannel, clientSocketChannel))
                        .filter(clientHold)
                        .filter(new AckFilterLayer())
                        .filter(new SSLEngineFilterLayer(clientEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "client"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        HoldFilterLayer serverHold = new HoldFilterLayer();
        ProtocolStack<Future<Channel>> server =
                ProtocolStack.on(new NIONetworkLayer(selector.hub(), serverSocketChannel, serverSocketChannel))
                        .filter(serverHold)
                        .filter(new AckFilterLayer("ACk"))
                        .filter(new SSLEngineFilterLayer(serverEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "server"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));
        clientHold.release();
        serverHold.release();
        try {
            client.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(
                    ClosedChannelException.class)));
        }
        try {
            server.get().get().call(new ProbeCallable());
            fail("Expected Connection refusal");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), anyOf(instanceOf(ConnectionRefusalException.class), instanceOf(
                    ClosedChannelException.class)));
        }
    }

    @Theory
    public void pipeSaturation(NetworkLayerFactory clientFactory, NetworkLayerFactory serverFactory) throws Exception {
        Pipe clientToServer = Pipe.open();
        Pipe serverToClient = Pipe.open();
        SSLEngine serverEngine = context.createSSLEngine();
        serverEngine.setUseClientMode(false);
        serverEngine.setNeedClientAuth(true);
        SSLEngine clientEngine = context.createSSLEngine();
        clientEngine.setUseClientMode(true);

        ProtocolStack<Future<Channel>> client =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), serverToClient.source(), clientToServer.sink()))
                        .filter(new AckFilterLayer("ACK"))
                        .filter(new SSLEngineFilterLayer(clientEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "client"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), clientToServer.source(), serverToClient.sink()))
                        .filter(new AckFilterLayer("ACK"))
                        .filter(new SSLEngineFilterLayer(serverEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "server"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));
        final hudson.remoting.Pipe p = hudson.remoting.Pipe.createLocalToRemote();

        Thread writer = new Thread() {
            final Thread mainThread = Thread.currentThread();
            // this makes it easy to see the relationship between the thread pair in the debugger

            @Override
            public void run() {
                OutputStream os = p.getOut();
                try {
                    byte[] buf = new byte[Channel.PIPE_WINDOW_SIZE * 2 + 1];
                    os.write(buf);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };

        Channel channel = client.get().get();

        // 1. wait until the receiver sees the first byte. at this point the pipe should be completely clogged
        // 2. make sure the writer thread is still alive, blocking
        // 3. read the rest

        ISaturationTest target = channel.call(new CreateSaturationTestProxy(p));

        // make sure the pipe is connected
        target.ensureConnected();
        channel.syncLocalIO();
        // then let the writer commence
        writer.start();

        // make sure that some data arrived to the receiver
        // at this point the pipe should be fully clogged
        assertThat(target.readFirst(), is(0));

        // the writer should be still blocked
        Thread.sleep(1000);
        assertThat(writer.isAlive(), is(true));

        target.readRest();
    }

    @Theory
    public void socketSaturation(NetworkLayerFactory clientFactory, NetworkLayerFactory serverFactory)
            throws Exception {
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

        ProtocolStack<Future<Channel>> client =
                ProtocolStack
                        .on(clientFactory.create(selector.hub(), clientSocketChannel, clientSocketChannel))
                        .filter(new AckFilterLayer("ACK"))
                        .filter(new SSLEngineFilterLayer(clientEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "client"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));

        ProtocolStack<Future<Channel>> server =
                ProtocolStack
                        .on(serverFactory.create(selector.hub(), serverSocketChannel, serverSocketChannel))
                        .filter(new AckFilterLayer("ACK"))
                        .filter(new SSLEngineFilterLayer(serverEngine, null))
                        .filter(new ConnectionHeadersFilterLayer(Collections.singletonMap("id", "server"),
                                new ConnectionHeadersFilterLayer.Listener() {
                                    @Override
                                    public void onReceiveHeaders(Map<String, String> headers)
                                            throws ConnectionRefusalException {
                                    }
                                }))
                        .build(new ChannelApplicationLayer(selector.executorService(), null));
        final hudson.remoting.Pipe p = hudson.remoting.Pipe.createLocalToRemote();

        Thread writer = new Thread() {
            final Thread mainThread = Thread.currentThread();
            // this makes it easy to see the relationship between the thread pair in the debugger

            @Override
            public void run() {
                OutputStream os = p.getOut();
                try {
                    byte[] buf = new byte[Channel.PIPE_WINDOW_SIZE * 2 + 1];
                    os.write(buf);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };

        Channel channel = client.get().get();

        // 1. wait until the receiver sees the first byte. at this point the pipe should be completely clogged
        // 2. make sure the writer thread is still alive, blocking
        // 3. read the rest

        ISaturationTest target = channel.call(new CreateSaturationTestProxy(p));

        // make sure the pipe is connected
        target.ensureConnected();
        channel.syncLocalIO();
        // then let the writer commence
        writer.start();

        // make sure that some data arrived to the receiver
        // at this point the pipe should be fully clogged
        assertThat(target.readFirst(), is(0));

        // the writer should be still blocked
        Thread.sleep(1000);
        assertThat(writer.isAlive(), is(true));

        target.readRest();
    }

    public interface ISaturationTest {
        void ensureConnected() throws IOException;

        int readFirst() throws IOException;

        void readRest() throws IOException;
    }

    private static class ProbeCallable implements Callable<String, IOException> {

        @Override
        public String call() throws IOException {
            System.out.println("Hello from: " + Channel.current());
            return null;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {

        }
    }

    private static class CreateSaturationTestProxy implements Callable<ISaturationTest, IOException> {
        private final hudson.remoting.Pipe pipe;

        public CreateSaturationTestProxy(hudson.remoting.Pipe pipe) {
            this.pipe = pipe;
        }

        public ISaturationTest call() throws IOException {
            return Channel.current().export(ISaturationTest.class, new ISaturationTest() {
                private InputStream in;

                public void ensureConnected() throws IOException {
                    in = pipe.getIn();
                }

                public int readFirst() throws IOException {
                    return in.read();
                }

                public void readRest() throws IOException {
                    new DataInputStream(in).readFully(new byte[Channel.PIPE_WINDOW_SIZE * 2]);
                }
            });
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {

        }
    }


}
