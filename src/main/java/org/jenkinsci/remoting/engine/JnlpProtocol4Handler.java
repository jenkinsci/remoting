package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
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

public class JnlpProtocol4Handler extends JnlpProtocolHandler<Jnlp4ConnectionState> {
    private static final Logger LOGGER = Logger.getLogger(JnlpProtocol4Handler.class.getName());
    @Nonnull
    private final ExecutorService threadPool;
    @Nonnull
    private final IOHub ioHub;
    @Nonnull
    private final SSLContext context;

    public JnlpProtocol4Handler(@Nullable JnlpClientDatabase clientDatabase, @Nonnull ExecutorService threadPool,
                                @Nonnull IOHub ioHub, @Nonnull SSLContext context) {
        super(clientDatabase);
        this.threadPool = threadPool;
        this.ioHub = ioHub;
        this.context = context;
    }

    /**
     * Get the name of the protocol.
     */
    @Override
    public String getName() {
        return "JNLP4-connect";
    }

    @Nonnull
    @Override
    public Jnlp4ConnectionState createConnectionState(@Nonnull Socket socket,
                                                      @Nonnull List<? extends JnlpConnectionStateListener> listeners) {
        return new Jnlp4ConnectionState(socket, listeners);
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
        SSLEngine engine = createSSLEngine(socket);
        engine.setWantClientAuth(true);
        engine.setNeedClientAuth(false);
        engine.setUseClientMode(false);
        Handler handler = new Handler(createConnectionState(socket, listeners), getClientDatabase());
        return ProtocolStack.on(networkLayer)
                .filter(new AckFilterLayer())
                .filter(new SSLEngineFilterLayer(engine, handler))
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

    private NetworkLayer createNetworkLayer(Socket socket) throws IOException {
        NetworkLayer networkLayer;
        SocketChannel socketChannel = socket.getChannel();
        if (socketChannel == null) {
            networkLayer = new BIONetworkLayer(ioHub, Channels.newChannel(socket.getInputStream()),
                    Channels.newChannel(socket.getOutputStream()));
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
    private class Handler extends Channel.Listener implements SSLEngineFilterLayer.Listener,
            ConnectionHeadersFilterLayer.Listener, ChannelApplicationLayer.Listener, ProtocolStack.Listener,
            ChannelApplicationLayer.ChannelDecorator {
        /**
         * The event.
         */
        @NonNull
        private final Jnlp4ConnectionState event;
        /**
         * The remote {@link X509Certificate}.
         */
        @CheckForNull
        private X509Certificate remoteCertificate;
        /**
         * The remote conection headers.
         */
        @edu.umd.cs.findbugs.annotations.Nullable
        private Map<String, String> remoteHeaders;

        /**
         * The client database used when {@link JnlpProtocol4Handler#handle(Socket, Map, List)} is handling a client.
         */
        private JnlpClientDatabase clientDatabase;

        /**
         * {@code true} when invoked from {@link JnlpProtocol4Handler#connect(Socket, Map, List)}, {@code false} when
         * invoked from {@link JnlpProtocol4Handler#handle(Socket, Map, List)}.
         */
        private boolean client;

        /**
         * Internal constructor for {@link JnlpProtocol4Handler#connect(Socket, Map, List)}.
         *
         * @param event the event.
         */
        Handler(Jnlp4ConnectionState event) {
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
            try {
                remoteCertificate = (X509Certificate) session.getPeerCertificates()[0];
            } catch (ClassCastException e) {
                // shouldn't happen as handshake is completed, but just in case
                throw new ConnectionRefusalException("Unsupported server certificate type", e);
            } catch (SSLPeerUnverifiedException e) {
                // shouldn't happen as handshake is completed, but just in case
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
                String secretKey = clientDatabase.getSecretOf(clientName);
                if (secretKey == null) {
                    throw new ConnectionRefusalException("Unknown client name: " + clientName);
                }
                if (!secretKey.equals(headers.get(JnlpConnectionState.SECRET_KEY))) {
                    LOGGER.log(Level.WARNING, "An attempt was made to connect as {0} from {1} with an incorrect secret",
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
        public void onChannel(@NonNull final Channel channel) {
            assert remoteHeaders != null;
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
        @NonNull
        public ChannelBuilder decorate(@NonNull ChannelBuilder builder) {
            assert remoteHeaders != null;
            event.fireBeforeChannel(builder);
            return event.getChannelBuilder();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onClosed(Channel channel, IOException cause) {
            assert remoteHeaders != null;
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
