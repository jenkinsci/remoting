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
import javax.annotation.Nonnull;
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
    @Nonnull
    private final ExecutorService threadPool;
    @Nonnull
    private final IOHub ioHub;
    @Nonnull
    private final SSLContext context;

    public JnlpProtocol4Handler(@Nonnull ExecutorService threadPool, @Nonnull IOHub ioHub, @Nonnull SSLContext context) {
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

    @Override
    public Jnlp4ConnectionState createConnectionState(Socket socket, List<JnlpConnectionStateListener> listeners) {
        return new Jnlp4ConnectionState(socket, listeners);
    }

    /**
     * Handles an incoming client connection on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send to the client.
     * @param listeners the listeners to process the connection.
     */
    @Override
    public Future<Channel> handle(Socket socket, Map<String, String> headers, List<JnlpConnectionStateListener> listeners)
            throws IOException {
        NetworkLayer networkLayer = createNetworkLayer(socket);
        SSLEngine engine = createSSLEngine(socket);
        engine.setNeedClientAuth(true);
        engine.setUseClientMode(false);
        Handler handler = new Handler(createConnectionState(socket, listeners));
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
     *  @param socket  the socket.
     * @param headers the headers to send to the server.
     * @param listeners the listeners to process the connection.
     */
    @Override
    public Future<Channel> connect(Socket socket, Map<String, String> headers, List<JnlpConnectionStateListener> listeners) throws IOException {
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
    private static class Handler extends Channel.Listener implements SSLEngineFilterLayer.Listener,
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
        @edu.umd.cs.findbugs.annotations.Nullable
        private X509Certificate remoteCertificate;
        /**
         * The remote conection headers.
         */
        @edu.umd.cs.findbugs.annotations.Nullable
        private Map<String, String> remoteHeaders;

        /**
         * Internal constructor.
         *
         * @param event the event.
         */
        Handler(Jnlp4ConnectionState event) {
            this.event = event;
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
                throw new ConnectionRefusalException("Server certificate unverified", e);
            }
            assert remoteCertificate != null;
            event.fireBeforeProperties(remoteCertificate);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onReceiveHeaders(Map<String, String> headers) throws ConnectionRefusalException {
            assert remoteCertificate != null;
            event.fireAfterProperties(headers);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onChannel(@NonNull Channel channel) {
            assert remoteCertificate != null;
            assert remoteHeaders != null;
            channel.addListener(this);
            event.fireAfterChannel(channel);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        @NonNull
        public ChannelBuilder decorate(@NonNull ChannelBuilder builder) {
            assert remoteCertificate != null;
            assert remoteHeaders != null;
            event.fireBeforeChannel(builder);
            return event.getChannelBuilder();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onClosed(Channel channel, IOException cause) {
            assert remoteCertificate != null;
            assert remoteHeaders != null;
            event.fireChannelClosed(cause);
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
            }
        }
    }


}
