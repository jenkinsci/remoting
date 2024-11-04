package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.Engine;
import hudson.remoting.EngineListenerSplitter;
import hudson.remoting.JarCache;
import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.cert.DelegatingX509ExtendedTrustManager;
import org.jenkinsci.remoting.protocol.cert.PublicKeyMatchingX509ExtendedTrustManager;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;
import org.jenkinsci.remoting.util.KeyUtils;
import org.jenkinsci.remoting.util.SSLUtils;

/**
 * Connects to a controller using inbound TCP.
 */
public class InboundTCPConnector extends AbstractEndpointConnector {
    private static final Logger LOGGER = Logger.getLogger(InboundTCPConnector.class.getName());

    private final JnlpEndpointResolver jnlpEndpointResolver;
    private final List<URL> candidateUrls;
    private final DelegatingX509ExtendedTrustManager agentTrustManager;
    private final boolean keepAlive;

    private URL url;
    /**
     * Name of the protocol that was used to successfully connect to the controller.
     */
    private String protocolName;

    /**
     * Tracks {@link Closeable} resources that need to be closed when this connector is closed.
     */
    @NonNull
    private final List<Closeable> closeables = new ArrayList<>();

    @Override
    public URL getUrl() {
        return url;
    }

    @SuppressFBWarnings(value = "HARD_CODE_PASSWORD", justification = "Password doesn't need to be protected.")
    public InboundTCPConnector(
            @NonNull String agentName,
            @NonNull String secretKey,
            @NonNull ExecutorService executor,
            @NonNull EngineListenerSplitter events,
            @NonNull Duration noReconnectAfter,
            @CheckForNull List<X509Certificate> candidateCertificates,
            boolean disableHttpsCertValidation,
            @CheckForNull JarCache jarCache,
            @CheckForNull String proxyCredentials,
            @NonNull List<URL> candidateUrls,
            @CheckForNull DelegatingX509ExtendedTrustManager agentTrustManager,
            boolean keepAlive,
            @NonNull JnlpEndpointResolver jnlpEndpointResolver) {
        super(
                agentName,
                secretKey,
                executor,
                events,
                noReconnectAfter,
                candidateCertificates,
                disableHttpsCertValidation,
                jarCache,
                proxyCredentials);
        this.candidateUrls = new ArrayList<>(candidateUrls);
        this.agentTrustManager = agentTrustManager;
        this.keepAlive = keepAlive;
        this.jnlpEndpointResolver = jnlpEndpointResolver;
    }

    private class EngineJnlpConnectionStateListener extends JnlpConnectionStateListener {

        private final RSAPublicKey publicKey;
        private final Map<String, String> headers;

        public EngineJnlpConnectionStateListener(RSAPublicKey publicKey, Map<String, String> headers) {
            this.publicKey = publicKey;
            this.headers = headers;
        }

        @Override
        public void beforeProperties(@NonNull JnlpConnectionState event) {
            if (event instanceof Jnlp4ConnectionState) {
                X509Certificate certificate = ((Jnlp4ConnectionState) event).getCertificate();
                if (certificate != null) {
                    String fingerprint = KeyUtils.fingerprint(certificate.getPublicKey());
                    if (!KeyUtils.equals(publicKey, certificate.getPublicKey())) {
                        event.reject(new ConnectionRefusalException("Expecting identity " + fingerprint));
                    }
                    events.status("Remote identity confirmed: " + fingerprint);
                }
            }
        }

        @Override
        public void afterProperties(@NonNull JnlpConnectionState event) {
            event.approve();
        }

        @Override
        public void beforeChannel(@NonNull JnlpConnectionState event) {
            ChannelBuilder bldr = event.getChannelBuilder().withMode(Channel.Mode.BINARY);
            if (jarCache != null) {
                bldr.withJarCache(jarCache);
            }
        }

        @Override
        public void afterChannel(@NonNull JnlpConnectionState event) {
            // store the new cookie for next connection attempt
            String cookie = event.getProperty(JnlpConnectionState.COOKIE_KEY);
            if (cookie == null) {
                headers.remove(JnlpConnectionState.COOKIE_KEY);
            } else {
                headers.put(JnlpConnectionState.COOKIE_KEY, cookie);
            }
        }
    }

    @Override
    public Future<Channel> connect() throws Exception {
        var hub = IOHub.create(this.executor);
        closeables.add(hub);
        var context= SSLUtils.createSSLContext(agentTrustManager);

        final JnlpAgentEndpoint endpoint = RetryUtils.succeedsWithRetries(
                jnlpEndpointResolver::resolve,
                noReconnectAfter,
                events,
                x -> "Could not locate server among " + candidateUrls + ": " + x.getMessage());
        if (endpoint == null) {
            this.events.status("Could not resolve server among " + this.candidateUrls);
            return null;
        }
        url = endpoint.getServiceUrl();

        this.events.status(String.format(
                "Agent discovery successful%n"
                        + "  Agent address: %s%n"
                        + "  Agent port:    %d%n"
                        + "  Identity:      %s",
                endpoint.getHost(), endpoint.getPort(), KeyUtils.fingerprint(endpoint.getPublicKey())));
        PublicKeyMatchingX509ExtendedTrustManager delegate = new PublicKeyMatchingX509ExtendedTrustManager();
        RSAPublicKey publicKey = endpoint.getPublicKey();
        if (publicKey != null) {
            // This is so that JNLP4-connect will only connect if the public key matches
            // if the public key is not published then JNLP4-connect will refuse to connect
            delegate.add(publicKey);
        }
        this.agentTrustManager.setDelegate(delegate);

        this.events.status("Handshaking");
        // must be read-write
        final Map<String, String> headers = new HashMap<>();
        headers.put(JnlpConnectionState.CLIENT_NAME_KEY, this.agentName);
        headers.put(JnlpConnectionState.SECRET_KEY, this.secretKey);
        // Create the protocols that will be attempted to connect to the controller.
        var clientProtocols = new JnlpProtocolHandlerFactory(this.executor)
                .withIOHub(hub)
                .withSSLContext(context)
                .withPreferNonBlockingIO(false) // we only have one connection, prefer blocking I/O
                .handlers();
        var negotiatedProtocols = clientProtocols.stream()
                .filter(JnlpProtocolHandler::isEnabled)
                .filter(p -> endpoint.isProtocolSupported(p.getName()))
                .collect(Collectors.toSet());
        var serverProtocols = endpoint.getProtocols() == null ? "?" : String.join(",", endpoint.getProtocols());
        LOGGER.info(buildDebugProtocolsMessage(serverProtocols, clientProtocols, negotiatedProtocols));
        for (var protocol : negotiatedProtocols) {
            var jnlpSocket = RetryUtils.succeedsWithRetries(
                    () -> {
                        events.status("Connecting to " + endpoint.describe() + " using " + protocol.getName());
                        // default is 30 mins. See PingThread for the ping interval
                        final Socket s = endpoint.open(Engine.SOCKET_TIMEOUT);
                        s.setKeepAlive(keepAlive);
                        return s;
                    },
                    noReconnectAfter,
                    events);
            if (jnlpSocket == null) {
                return null;
            }
            closeables.add(jnlpSocket);
            try {
                protocolName = protocol.getName();
                return protocol.connect(
                        jnlpSocket, headers, new EngineJnlpConnectionStateListener(endpoint.getPublicKey(), headers));
            } catch (IOException ioe) {
                events.status("Protocol " + protocol.getName() + " failed to establish channel", ioe);
                protocolName = null;
            } catch (RuntimeException e) {
                events.status("Protocol " + protocol.getName() + " encountered a runtime error", e);
                protocolName = null;
            }
            // On failure form a new connection.
            jnlpSocket.close();
            closeables.remove(jnlpSocket);
        }
        if (negotiatedProtocols.isEmpty()) {
            events.status(
                    "reconnect rejected",
                    new Exception("The server rejected the connection: None of the protocols were accepted"));
        } else {
            events.status(
                    "reconnect rejected",
                    new Exception("The server rejected the connection: None of the protocols are enabled"));
        }
        return null;
    }

    @NonNull
    private static String buildDebugProtocolsMessage(String serverProtocols, List<JnlpProtocolHandler<? extends JnlpConnectionState>> clientProtocols, Set<JnlpProtocolHandler<? extends JnlpConnectionState>> negotiatedProtocols) {
        return "Protocols support: Server " + "[" + serverProtocols + "]"
                + ", Client " + "["
                + clientProtocols.stream()
                .map(p -> p.getName() + (!p.isEnabled() ? " (disabled)" : ""))
                .collect(Collectors.joining(","))
                + "]"
                + ", Negociated: " + "["
                + negotiatedProtocols.stream().map(JnlpProtocolHandler::getName).collect(Collectors.joining(","))
                + "]";
    }

    @Override
    public Boolean waitUntilReady() throws InterruptedException {
        jnlpEndpointResolver.waitForReady();
        return true;
    }

    @Override
    public String getProtocol() {
        return protocolName;
    }

    @Override
    public void close() {
        closeables.forEach(c -> {
            try {
                c.close();
            } catch (IOException e) {
                events.status("Failed to close resource " + c, e);
            }
        });
    }
}
