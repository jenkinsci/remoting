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

import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.SocketChannelStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLEngine;
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

/**
 * Implements the JNLP4-plaintext-connect protocol. This protocol uses {@link SSLEngine} to perform a TLS upgrade of
 * the plaintext
 * connection before any connection secrets are exchanged. The subsequent connection is then secured using TLS. The
 * implementation uses the {@link IOHub} for non-blocking I/O wherever possible which removes the bottleneck of
 * the selector thread being used for linearization and I/O that creates a throughput limit with {@link NioChannelHub}.
 *
 * @since FIXME
 */
public class JnlpProtocol4PlainHandler extends JnlpProtocolHandler<JnlpConnectionState> {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(JnlpProtocol4PlainHandler.class.getName());
    /**
     * The thread pool we can use for executing tasks.
     */
    @Nonnull
    private final ExecutorService threadPool;
    /**
     * The {@link IOHub}.
     */
    @Nonnull
    private final IOHub ioHub;

    /**
     * Constructor.
     *
     * @param clientDatabase the client database.
     * @param threadPool     the thread pool.
     * @param ioHub          the {@link IOHub}.
     * @param preferNio      {@code true} means that the protocol should attempt to use NIO if possible
     */
    public JnlpProtocol4PlainHandler(@Nullable JnlpClientDatabase clientDatabase, @Nonnull ExecutorService threadPool,
                                     @Nonnull IOHub ioHub, boolean preferNio) {
        super(clientDatabase, preferNio);
        this.threadPool = threadPool;
        this.ioHub = ioHub;
    }

    /**
     * Get the name of the protocol.
     */
    @Override
    public String getName() {
        return "JNLP4-plaintext";
    }

    @Nonnull
    @Override
    public JnlpConnectionState createConnectionState(@Nonnull Socket socket,
                                                     @Nonnull List<? extends JnlpConnectionStateListener> listeners) {
        return new JnlpConnectionState(socket, listeners);
    }

    /**
     * Handles an incoming client connection on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send to the client.
     * @param listeners the listeners to process the connection.
     */
    @Nonnull
    @Override
    public Future<Channel> handle(@Nonnull Socket socket, @Nonnull Map<String, String> headers,
                                  @Nonnull List<? extends JnlpConnectionStateListener> listeners)
            throws IOException {
        NetworkLayer networkLayer = createNetworkLayer(socket);
        Handler handler = new Handler(createConnectionState(socket, listeners), getClientDatabase());
        return ProtocolStack.on(networkLayer)
                .filter(new AckFilterLayer())
                .filter(new ConnectionHeadersFilterLayer(headers, handler))
                .named(String.format("%s connection from %s", getName(), socket.getRemoteSocketAddress()))
                .listener(handler)
                .build(new ChannelApplicationLayer(threadPool, handler))
                .get();
    }

    /**
     * Handles an outgoing connection to the server on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send to the server.
     * @param listeners the listeners to process the connection.
     */
    @Nonnull
    @Override
    public Future<Channel> connect(@Nonnull Socket socket, @Nonnull Map<String, String> headers,
                                   @Nonnull List<? extends JnlpConnectionStateListener> listeners) throws IOException {
        NetworkLayer networkLayer = createNetworkLayer(socket);
        Handler handler = new Handler(createConnectionState(socket, listeners));
        return ProtocolStack.on(networkLayer)
                .filter(new AgentProtocolClientFilterLayer(getName()))
                .filter(new AckFilterLayer())
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
        if (socketChannel == null) {
            networkLayer = new BIONetworkLayer(ioHub, Channels.newChannel(SocketChannelStream.in(socket)),
                    Channels.newChannel(SocketChannelStream.out(socket)));
        } else {
            networkLayer = new NIONetworkLayer(ioHub, socketChannel, socketChannel);
        }
        return networkLayer;
    }

    /**
     * Handles the state transitions for a connection.
     */
    private class Handler extends Channel.Listener implements ConnectionHeadersFilterLayer.Listener,
            ChannelApplicationLayer.Listener, ProtocolStack.Listener,
            ChannelApplicationLayer.ChannelDecorator {
        /**
         * The event.
         */
        @Nonnull
        private final JnlpConnectionState event;

        /**
         * The client database used when {@link JnlpProtocol4PlainHandler#handle(Socket, Map, List)} is handling a
         * client.
         */
        private JnlpClientDatabase clientDatabase;

        /**
         * {@code true} when invoked from {@link JnlpProtocol4PlainHandler#connect(Socket, Map, List)}, {@code false}
         * when
         * invoked from {@link JnlpProtocol4PlainHandler#handle(Socket, Map, List)}.
         */
        private boolean client;

        /**
         * Internal constructor for {@link JnlpProtocol4PlainHandler#connect(Socket, Map, List)}.
         *
         * @param event the event.
         */
        Handler(JnlpConnectionState event) {
            this.event = event;
            this.client = true;
        }

        /**
         * Internal constructor for {@link JnlpProtocol4PlainHandler#handle(Socket, Map, List)}.
         *
         * @param event          the event.
         * @param clientDatabase the client database.
         */
        Handler(@Nonnull JnlpConnectionState event, JnlpClientDatabase clientDatabase) {
            this.event = event;
            this.clientDatabase = clientDatabase;
            this.client = false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onReceiveHeaders(Map<String, String> headers) throws ConnectionRefusalException {
            event.fireBeforeProperties();
            if (!client) {
                String clientName = headers.get(JnlpConnectionState.CLIENT_NAME_KEY);
                if (clientDatabase == null || !clientDatabase.exists(clientName)) {
                    throw new ConnectionRefusalException("Unknown client name: " + clientName);
                }
                String secretKey = clientDatabase.getSecretOf(clientName);
                if (secretKey == null) {
                    // should never get hear unless there is a race condition in removing an entry from the DB
                    throw new ConnectionRefusalException("Unknown client name: " + clientName);
                }
                if (!secretKey.equals(headers.get(JnlpConnectionState.SECRET_KEY))) {
                    LOGGER.log(Level.WARNING,
                            "An attempt was made to connect as {0} from {1} with an incorrect secret",
                            new Object[]{clientName, event.getSocket().getRemoteSocketAddress()});
                    throw new ConnectionRefusalException("Authorization failure");
                }
            }
            event.fireAfterProperties(headers);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onChannel(@Nonnull final Channel channel) {
            channel.addListener(this);
            threadPool.execute(new Runnable() {
                @Override
                public void run() {
                    if (!channel.isClosingOrClosed()) {
                        event.fireAfterChannel(channel);
                    }
                }
            });
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @Nonnull
        public ChannelBuilder decorate(@Nonnull ChannelBuilder builder) {
            event.fireBeforeChannel(builder);
            return event.getChannelBuilder();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onClosed(Channel channel, IOException cause) {
            event.fireChannelClosed(cause);
            try {
                event.getSocket().close();
            } catch (IOException e) {
                // ignore
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onClosed(ProtocolStack stack, IOException cause) {
            try {
                event.fireAfterDisconnect();
            } finally {
                stack.removeListener(this);
                try {
                    event.getSocket().close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }


}
