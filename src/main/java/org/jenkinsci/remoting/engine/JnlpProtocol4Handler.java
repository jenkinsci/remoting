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
package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.SocketChannelStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.jenkinsci.remoting.nio.NioChannelHub;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.NetworkLayer;
import org.jenkinsci.remoting.protocol.ProtocolStack;
import org.jenkinsci.remoting.protocol.impl.AckFilterLayer;
import org.jenkinsci.remoting.protocol.impl.AgentProtocolClientFilterLayer;
import org.jenkinsci.remoting.protocol.impl.BIONetworkLayer;
import org.jenkinsci.remoting.protocol.impl.ChannelApplicationLayer;
import org.jenkinsci.remoting.protocol.impl.ConnectionHeadersFilterLayer;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;
import org.jenkinsci.remoting.protocol.impl.NIONetworkLayer;
import org.jenkinsci.remoting.protocol.impl.SSLEngineFilterLayer;
import org.jenkinsci.remoting.util.IOUtils;

/**
 * Implements the JNLP4-connect protocol. This protocol uses {@link SSLEngine} to perform a TLS upgrade of the plaintext
 * connection before any connection secrets are exchanged. The subsequent connection is then secured using TLS. The
 * implementation uses the {@link IOHub} for non-blocking I/O wherever possible which removes the bottleneck of
 * the selector thread being used for linearization and I/O that creates a throughput limit with {@link NioChannelHub}.
 *
 * @since 3.0
 */
public class JnlpProtocol4Handler extends JnlpProtocolHandler<Jnlp4ConnectionState> {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(JnlpProtocol4Handler.class.getName());
    /**
     * The thread pool we can use for executing tasks.
     */
    @NonNull
    private final ExecutorService threadPool;
    /**
     * The {@link IOHub}.
     */
    @NonNull
    private final IOHub ioHub;
    /**
     * The {@link SSLContext}
     */
    @NonNull
    private final SSLContext context;
    /**
     * Flag to indicate whether client authentication is reported as required or optional by the server.
     */
    private final boolean needClientAuth;

    /**
     * Constructor.
     *
     * @param clientDatabase the client database.
     * @param threadPool     the thread pool.
     * @param ioHub          the {@link IOHub}.
     * @param context        the {@link SSLContext}.
     * @param needClientAuth {@code} to force all clients to have a client certificate in order to connect.
     * @param preferNio      {@code true} means that the protocol should attempt to use NIO if possible.
     */
    public JnlpProtocol4Handler(
            @Nullable JnlpClientDatabase clientDatabase,
            @NonNull ExecutorService threadPool,
            @NonNull IOHub ioHub,
            @NonNull SSLContext context,
            boolean needClientAuth,
            boolean preferNio) {
        super(clientDatabase, preferNio);
        this.threadPool = threadPool;
        this.ioHub = ioHub;
        this.context = context;
        this.needClientAuth = needClientAuth;
    }

    /**
     * Get the name of the protocol.
     */
    @Override
    public String getName() {
        return "JNLP4-connect";
    }

    @NonNull
    @Override
    public Jnlp4ConnectionState createConnectionState(
            @NonNull Socket socket, @NonNull List<? extends JnlpConnectionStateListener> listeners) {
        return new Jnlp4ConnectionState(socket, listeners);
    }

    /**
     * Handles an incoming client connection on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send to the client.
     * @param listeners the listeners to process the connection.
     */
    @NonNull
    @Override
    public Future<Channel> handle(
            @NonNull Socket socket,
            @NonNull Map<String, String> headers,
            @NonNull List<? extends JnlpConnectionStateListener> listeners)
            throws IOException {
        NetworkLayer networkLayer = createNetworkLayer(socket);
        SSLEngine engine = createSSLEngine(socket);
        engine.setWantClientAuth(true);
        engine.setNeedClientAuth(needClientAuth);
        engine.setUseClientMode(false);
        Handler handler = new Handler(createConnectionState(socket, listeners), getClientDatabase());
        return ProtocolStack.on(networkLayer)
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(engine, handler))
                .filter(new ConnectionHeadersFilterLayer(headers, handler))
                .named(String.format("%s connection from %s", getName(), socket.getRemoteSocketAddress()))
                .listener(handler)
                .build(new ChannelApplicationLayer(threadPool, handler, headers.get(JnlpConnectionState.COOKIE_KEY)))
                .get();
    }

    /**
     * Handles an outgoing connection to the server on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send to the server.
     * @param listeners the listeners to process the connection.
     */
    @NonNull
    @Override
    public Future<Channel> connect(
            @NonNull Socket socket,
            @NonNull Map<String, String> headers,
            @NonNull List<? extends JnlpConnectionStateListener> listeners)
            throws IOException {
        NetworkLayer networkLayer = createNetworkLayer(socket);
        SSLEngine sslEngine = createSSLEngine(socket);
        sslEngine.setUseClientMode(true);
        Handler handler = new Handler(createConnectionState(socket, listeners));
        return ProtocolStack.on(networkLayer)
                .filter(new AgentProtocolClientFilterLayer(getName()))
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(sslEngine, handler))
                .filter(new ConnectionHeadersFilterLayer(headers, handler))
                .named(String.format("%s connection to %s", getName(), socket.getRemoteSocketAddress()))
                .listener(handler)
                .build(new ChannelApplicationLayer(threadPool, handler))
                .get();
    }

    /**
     * Creates the best network layer for the provided {@link Socket}.
     *
     * @param socket the {@link Socket}.
     * @return the best {@link NetworkLayer} for the provided {@link Socket}.
     * @throws IOException if the socket is closed already.
     */
    private NetworkLayer createNetworkLayer(Socket socket) throws IOException {
        NetworkLayer networkLayer;
        SocketChannel socketChannel = isPreferNio() ? socket.getChannel() : null;
        LOGGER.fine(() -> "prefer NIO? " + isPreferNio() + " actually using NIO? " + (socketChannel != null));
        if (socketChannel == null) {
            networkLayer = new BIONetworkLayer(
                    ioHub,
                    Channels.newChannel(SocketChannelStream.in(socket)),
                    Channels.newChannel(SocketChannelStream.out(socket)));
        } else {
            networkLayer = new NIONetworkLayer(ioHub, socketChannel, socketChannel);
        }
        return networkLayer;
    }

    /**
     * Creates an {@link SSLEngine} for the specified {@link Socket}. The {@link SSLContext} may have retained session
     * state and thus enable a shorter handshake.
     *
     * @param socket the socket.
     * @return the {@link SSLEngine}.                                             s
     */
    private SSLEngine createSSLEngine(Socket socket) {
        SocketAddress remoteSocketAddress = socket.getRemoteSocketAddress();
        if (remoteSocketAddress instanceof InetSocketAddress) {
            InetSocketAddress remoteInetAddress = (InetSocketAddress) remoteSocketAddress;
            return context.createSSLEngine(remoteInetAddress.getHostName(), remoteInetAddress.getPort());
        } else {
            return context.createSSLEngine();
        }
    }

    /**
     * Handles the state transitions for a connection.
     */
    private class Handler extends Channel.Listener
            implements SSLEngineFilterLayer.Listener,
                    ConnectionHeadersFilterLayer.Listener,
                    ChannelApplicationLayer.Listener,
                    ProtocolStack.Listener,
                    ChannelApplicationLayer.ChannelDecorator {
        /**
         * The event.
         */
        @NonNull
        private final Jnlp4ConnectionState event;
        /**
         * The client database used when {@link JnlpProtocol4Handler#handle(Socket, Map, List)} is handling a client.
         */
        private JnlpClientDatabase clientDatabase;

        /**
         * {@code true} when invoked from {@link JnlpProtocol4Handler#connect(Socket, Map, List)}, {@code false} when
         * invoked from {@link JnlpProtocol4Handler#handle(Socket, Map, List)}.
         */
        private final boolean client;

        /**
         * Internal constructor for {@link JnlpProtocol4Handler#connect(Socket, Map, List)}.
         *
         * @param event the event.
         */
        Handler(@NonNull Jnlp4ConnectionState event) {
            this.event = event;
            this.client = true;
        }

        /**
         * Internal constructor for {@link JnlpProtocol4Handler#handle(Socket, Map, List)}.
         *
         * @param event          the event.
         * @param clientDatabase the client database.
         */
        Handler(@NonNull Jnlp4ConnectionState event, JnlpClientDatabase clientDatabase) {
            this.event = event;
            this.clientDatabase = clientDatabase;
            this.client = false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onHandshakeCompleted(SSLSession session) throws ConnectionRefusalException {
            X509Certificate remoteCertificate;
            try {
                remoteCertificate = (X509Certificate) session.getPeerCertificates()[0];
            } catch (ClassCastException e) {
                // shouldn't happen as handshake is completed, but just in case
                throw new ConnectionRefusalException("Unsupported server certificate type", e);
            } catch (SSLPeerUnverifiedException e) {
                if (needClientAuth) {
                    throw new ConnectionRefusalException("Client must provide authentication", e);
                }
                remoteCertificate = null;
            }
            event.fireBeforeProperties(remoteCertificate);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onReceiveHeaders(Map<String, String> headers) throws ConnectionRefusalException {
            if (!client) {
                String clientName = headers.get(JnlpConnectionState.CLIENT_NAME_KEY);
                if (clientDatabase == null || !clientDatabase.exists(clientName)) {
                    throw new ConnectionRefusalException("Unknown client name: " + clientName);
                }
                X509Certificate certificate = event.getCertificate();
                JnlpClientDatabase.ValidationResult validation = certificate == null
                        ? JnlpClientDatabase.ValidationResult.UNCHECKED
                        : clientDatabase.validateCertificate(clientName, certificate);
                switch (validation) {
                    case IDENTITY_PROVED:
                        // no-op, we trust the certificate as being from the client
                        break;
                    case INVALID:
                        LOGGER.log(
                                Level.WARNING,
                                "An attempt was made to connect as {0} from {1} with an invalid client certificate",
                                new Object[] {clientName, event.getRemoteEndpointDescription()});
                        throw new ConnectionRefusalException("Authentication failure");
                    default:
                        String secretKey = clientDatabase.getSecretOf(clientName);
                        if (secretKey == null) {
                            // should never get hear unless there is a race condition in removing an entry from the DB
                            throw new ConnectionRefusalException("Unknown client name: " + clientName);
                        }
                        if (!MessageDigest.isEqual(
                                secretKey.getBytes(StandardCharsets.UTF_8),
                                headers.get(JnlpConnectionState.SECRET_KEY).getBytes(StandardCharsets.UTF_8))) {
                            LOGGER.log(
                                    Level.WARNING,
                                    "An attempt was made to connect as {0} from {1} with an incorrect secret",
                                    new Object[] {clientName, event.getRemoteEndpointDescription()});
                            throw new ConnectionRefusalException("Authorization failure");
                        }
                        break;
                }
            }
            event.fireAfterProperties(headers);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onChannel(@NonNull final Channel channel) {
            channel.addListener(this);
            threadPool.execute(() -> {
                if (!channel.isClosingOrClosed()) {
                    event.fireAfterChannel(channel);
                }
            });
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public ChannelBuilder decorate(@NonNull ChannelBuilder builder) {
            if (!client) {
                builder.withMode(Channel.Mode.NEGOTIATE);
            }
            event.fireBeforeChannel(builder);
            return event.getChannelBuilder();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onClosed(Channel channel, IOException cause) {
            if (channel != event.getChannel()) {
                return;
            }
            event.fireChannelClosed(cause);
            channel.removeListener(this);
            IOUtils.closeQuietly(event.getSocket());
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onClosed(ProtocolStack<?> stack, IOException cause) {
            try {
                event.fireAfterDisconnect();
            } finally {
                stack.removeListener(this);
                IOUtils.closeQuietly(event.getSocket());
            }
        }
    }
}
