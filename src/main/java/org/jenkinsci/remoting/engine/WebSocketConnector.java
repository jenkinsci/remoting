package org.jenkinsci.remoting.engine;

import static org.jenkinsci.remoting.util.SSLUtils.getSSLContext;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.remoting.AbstractByteBufferCommandTransport;
import hudson.remoting.Capability;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.ChannelClosedException;
import hudson.remoting.ChunkHeader;
import hudson.remoting.Engine;
import hudson.remoting.EngineListenerSplitter;
import hudson.remoting.JarCache;
import hudson.remoting.Launcher;
import hudson.remoting.NoProxyEvaluator;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import org.glassfish.tyrus.client.SslEngineConfigurator;
import org.jenkinsci.remoting.util.VersionNumber;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Connects to a controller using WebSockets.
 */
@Restricted(NoExternalUse.class)
public class WebSocketConnector implements EndpointConnector {
    private static final Logger LOGGER = Logger.getLogger(WebSocketConnector.class.getName());
    private final EndpointConnectorData data;

    @Override
    @NonNull
    public URL getUrl() {
        return url;
    }

    /**
     * The URL to connect to.
     */
    @NonNull
    private final URL url;

    /**
     * Headers to be added to the initial request for connection.
     */
    @NonNull
    private final Map<String, String> headers;

    /**
     * A custom hostname verifier to use for HTTPS connections.
     */
    @CheckForNull
    private final HostnameVerifier hostnameVerifier;

    public WebSocketConnector(
            EndpointConnectorData data,
            @NonNull URL url,
            @CheckForNull Map<String, String> headers,
            @CheckForNull HostnameVerifier hostnameVerifier) {
        this.data = data;
        this.url = url;
        this.headers = headers == null ? Map.of() : new HashMap<>(headers);
        this.hostnameVerifier = hostnameVerifier;
    }

    @SuppressFBWarnings(
            value = {"URLCONNECTION_SSRF_FD", "NP_BOOLEAN_RETURN_NULL"},
            justification = "url is provided by the user, and we are trying to connect to it")
    private Boolean pingSuccessful() throws MalformedURLException {
        // Unlike JnlpAgentEndpointResolver, we do not use $jenkins/tcpSlaveAgentListener/, as that will be
        // a 404 if the TCP port is disabled.
        URL ping = new URL(url, "login");
        try {
            HttpURLConnection conn = (HttpURLConnection) ping.openConnection();
            int status = conn.getResponseCode();
            conn.disconnect();
            if (status == 200) {
                return Boolean.TRUE;
            } else {
                data.events().status(ping + " is not ready: " + status);
            }
        } catch (IOException x) {
            data.events().status(ping + " is not ready: " + x.getMessage());
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        // no-op
    }

    private static class HeaderHandler extends ClientEndpointConfig.Configurator {
        private final Map<String, List<String>> addedHeaders;
        private final EngineListenerSplitter events;
        private Capability remoteCapability;

        HeaderHandler(Map<String, List<String>> addedHeaders, EngineListenerSplitter events) {
            this.addedHeaders = new HashMap<>(addedHeaders);
            this.events = events;
            this.remoteCapability = new Capability();
        }

        @Override
        public void beforeRequest(Map<String, List<String>> headers) {
            headers.putAll(addedHeaders);
            LOGGER.fine(() -> "Sending: " + headers);
        }

        @Override
        public void afterResponse(HandshakeResponse hr) {
            LOGGER.fine(() -> "Receiving: " + hr.getHeaders());
            List<String> remotingMinimumVersion = hr.getHeaders().get(Engine.REMOTING_MINIMUM_VERSION_HEADER);
            if (remotingMinimumVersion != null && !remotingMinimumVersion.isEmpty()) {
                VersionNumber minimumSupportedVersion = new VersionNumber(remotingMinimumVersion.get(0));
                VersionNumber currentVersion = new VersionNumber(Launcher.VERSION);
                if (currentVersion.isOlderThan(minimumSupportedVersion)) {
                    events.error(
                            new IOException("Agent version " + minimumSupportedVersion + " or newer is required."));
                }
            }
            try {
                List<String> cookies = hr.getHeaders().get(Engine.WEBSOCKET_COOKIE_HEADER);
                if (cookies != null && !cookies.isEmpty()) {
                    addedHeaders.put(Engine.WEBSOCKET_COOKIE_HEADER, List.of(cookies.get(0)));
                } else {
                    addedHeaders.remove(Engine.WEBSOCKET_COOKIE_HEADER);
                }
                List<String> advertisedCapability = hr.getHeaders().get(Capability.KEY);
                if (advertisedCapability == null) {
                    LOGGER.warning("Did not receive " + Capability.KEY + " header");
                } else {
                    remoteCapability = Capability.fromASCII(advertisedCapability.get(0));
                    LOGGER.fine(() -> "received " + remoteCapability);
                }
            } catch (IOException x) {
                events.error(x);
            }
        }
    }

    private static class AgentEndpoint extends Endpoint {
        private final CompletableFuture<Channel> futureChannel;
        private final EngineListenerSplitter events;
        private final String agentName;
        private final ExecutorService executor;
        private final JarCache jarCache;
        private final Supplier<Capability> capabilitySupplier;

        AgentEndpoint(
                String agentName,
                ExecutorService executor,
                JarCache jarCache,
                Supplier<Capability> capabilitySupplier,
                EngineListenerSplitter events) {
            this.futureChannel = new CompletableFuture<>();
            this.agentName = agentName;
            this.executor = executor;
            this.jarCache = jarCache;
            this.capabilitySupplier = capabilitySupplier;
            this.events = events;
        }

        public Future<Channel> getChannel() {
            return futureChannel;
        }

        @SuppressFBWarnings(value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", justification = "just trust me here")
        AgentEndpoint.Transport transport;

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            events.status("WebSocket connection open");
            session.addMessageHandler(ByteBuffer.class, this::onMessage);
            try {
                transport = new Transport(session);
                futureChannel.complete(new ChannelBuilder(agentName, executor)
                        .withJarCacheOrDefault(jarCache)
                        . // unless EngineJnlpConnectionStateListener can be used for this purpose
                        build(transport));
            } catch (IOException x) {
                events.error(x);
            }
        }

        private void onMessage(ByteBuffer message) {
            try {
                transport.receive(message);
            } catch (IOException x) {
                events.error(x);
            } catch (InterruptedException x) {
                events.error(x);
                Thread.currentThread().interrupt();
            }
        }

        @Override
        @SuppressFBWarnings(
                value = "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE",
                justification =
                        "We want the transport.terminate method to run asynchronously and don't want to wait for its status.")
        public void onClose(Session session, CloseReason closeReason) {
            LOGGER.fine(() -> "onClose: " + closeReason);
            // making this call async to avoid potential deadlocks when some thread is holding a lock on the
            // channel object while this thread is trying to acquire it to call Transport#terminate
            var channel = futureChannel.join();
            channel.executor.submit(() -> transport.terminate(new ChannelClosedException(channel, null)));
        }

        @Override
        @SuppressFBWarnings(
                value = "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE",
                justification =
                        "We want the transport.terminate method to run asynchronously and don't want to wait for its status.")
        public void onError(Session session, Throwable x) {
            // TODO or would events.error(x) be better?
            LOGGER.log(Level.FINE, null, x);
            // as above
            var channel = futureChannel.join();
            channel.executor.submit(() -> transport.terminate(new ChannelClosedException(channel, x)));
        }

        class Transport extends AbstractByteBufferCommandTransport {
            final Session session;

            Transport(Session session) {
                super(true);
                this.session = session;
            }

            @Override
            protected void write(ByteBuffer headerAndData) throws IOException {
                LOGGER.finest(() -> "sending message of length " + (headerAndData.remaining() - ChunkHeader.SIZE));
                try {
                    session.getAsyncRemote().sendBinary(headerAndData).get(5, TimeUnit.MINUTES);
                } catch (Exception x) {
                    throw new IOException(x);
                }
            }

            @Override
            public Capability getRemoteCapability() {
                return capabilitySupplier.get();
            }

            @Override
            public void closeWrite() throws IOException {
                events.status("Write side closed");
                session.close();
            }

            @Override
            public void closeRead() throws IOException {
                events.status("Read side closed");
                session.close();
            }
        }
    }

    @Override
    public Future<Channel> connect() throws Exception {
        String localCap = new Capability().toASCII();
        final Map<String, List<String>> addedHeaders = new HashMap<>();
        addedHeaders.put(JnlpConnectionState.CLIENT_NAME_KEY, List.of(data.agentName()));
        addedHeaders.put(JnlpConnectionState.SECRET_KEY, List.of(data.secretKey()));
        addedHeaders.put(Capability.KEY, List.of(localCap));
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            addedHeaders.put(entry.getKey(), List.of(entry.getValue()));
        }
        String wsUrl = url.toString().replaceFirst("^http", "ws");
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        if (container instanceof ClientManager) {
            ClientManager client = (ClientManager) container;

            String proxyHost = System.getProperty("http.proxyHost", System.getenv("proxy_host"));
            String proxyPort = System.getProperty("http.proxyPort");
            if (proxyHost != null && "http".equals(url.getProtocol()) && NoProxyEvaluator.shouldProxy(url.getHost())) {
                URI proxyUri;
                if (proxyPort != null) {
                    proxyUri = URI.create(String.format("http://%s:%s", proxyHost, proxyPort));
                } else {
                    proxyUri = URI.create(String.format("http://%s", proxyHost));
                }
                client.getProperties().put(ClientProperties.PROXY_URI, proxyUri);
                if (data.proxyCredentials() != null) {
                    client.getProperties()
                            .put(
                                    ClientProperties.PROXY_HEADERS,
                                    Map.of(
                                            "Proxy-Authorization",
                                            "Basic "
                                                    + Base64.getEncoder()
                                                            .encodeToString(data.proxyCredentials()
                                                                    .getBytes(StandardCharsets.UTF_8))));
                }
            }

            SSLContext sslContext = getSSLContext(data.candidateCertificates(), data.disableHttpsCertValidation());
            if (sslContext != null) {
                SslEngineConfigurator sslEngineConfigurator = new SslEngineConfigurator(sslContext);
                if (hostnameVerifier != null) {
                    sslEngineConfigurator.setHostnameVerifier(hostnameVerifier);
                }
                client.getProperties().put(ClientProperties.SSL_ENGINE_CONFIGURATOR, sslEngineConfigurator);
            }
        }
        return RetryUtils.succeedsWithRetries(
                () -> {
                    var clientEndpointConfigurator = new HeaderHandler(addedHeaders, data.events());
                    var endpointInstance = new AgentEndpoint(
                            data.agentName(),
                            data.executor(),
                            data.jarCache(),
                            () -> clientEndpointConfigurator.remoteCapability,
                            data.events());
                    container.connectToServer(
                            endpointInstance,
                            ClientEndpointConfig.Builder.create()
                                    .configurator(clientEndpointConfigurator)
                                    .build(),
                            URI.create(wsUrl + "wsagents/"));
                    return endpointInstance.getChannel();
                },
                data.noReconnectAfter(),
                data.events());
    }

    @Override
    public Boolean waitUntilReady() throws InterruptedException {
        return RetryUtils.succeedsWithRetries(this::pingSuccessful, data.noReconnectAfter(), data.events());
    }

    @Override
    public String getProtocol() {
        return "WebSocket";
    }
}
