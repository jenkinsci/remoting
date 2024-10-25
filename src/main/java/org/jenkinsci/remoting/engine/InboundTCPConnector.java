package org.jenkinsci.remoting.engine;

import static org.jenkinsci.remoting.util.SSLUtils.getSSLSocketFactory;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.Engine;
import hudson.remoting.EngineListenerSplitter;
import hudson.remoting.JarCache;
import java.io.IOException;
import java.net.Socket;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
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
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.cert.DelegatingX509ExtendedTrustManager;
import org.jenkinsci.remoting.protocol.cert.PublicKeyMatchingX509ExtendedTrustManager;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;
import org.jenkinsci.remoting.util.KeyUtils;

/**
 * Connects to a controller using inbound TCP.
 */
public class InboundTCPConnector extends AbstractEndpointConnector {
    private static final Logger LOGGER = Logger.getLogger(InboundTCPConnector.class.getName());

    private final JnlpEndpointResolver resolver;
    private IOHub hub;
    private boolean noReconnect;
    private final List<URL> candidateUrls;
    private final DelegatingX509ExtendedTrustManager agentTrustManager;
    private final boolean keepAlive;

    private URL url;
    private String protocolName;
    private final String directConnection;
    private final String instanceIdentity;
    private final Set<String> protocols;
    private final String credentials;
    private final String tunnel;

    private List<JnlpProtocolHandler<? extends JnlpConnectionState>> protocolsImpls;
    private Socket jnlpSocket;

    @Override
    public URL getUrl() {
        return url;
    }

    @SuppressFBWarnings(value = "HARD_CODE_PASSWORD", justification = "Password doesn't need to be protected.")
    public InboundTCPConnector(
            @NonNull String agentName,
            @NonNull String secretKey,
            @NonNull ExecutorService executor,
            @NonNull List<URL> candidateUrls,
            @NonNull EngineListenerSplitter events,
            @CheckForNull DelegatingX509ExtendedTrustManager agentTrustManager,
            boolean noReconnect,
            @NonNull Duration noReconnectAfter,
            boolean keepAlive,
            String directConnection,
            @CheckForNull List<X509Certificate> candidateCertificates,
            boolean disableHttpsCertValidation,
            @CheckForNull JarCache jarCache,
            @CheckForNull String proxyCredentials,
            String instanceIdentity,
            Set<String> protocols,
            String credentials,
            String tunnel) {
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
        this.noReconnect = noReconnect;
        this.keepAlive = keepAlive;
        this.directConnection = directConnection;
        this.instanceIdentity = instanceIdentity;
        this.protocols = protocols;
        this.credentials = credentials;
        this.tunnel = tunnel;
        this.resolver = createEndpointResolver();

        // Create the engine
        try {
            hub = IOHub.create(this.executor);
            SSLContext context;
            // prepare our SSLContext
            try {
                context = SSLContext.getInstance("TLS");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Java runtime specification requires support for TLS algorithm", e);
            }
            char[] password = "password".toCharArray();
            KeyStore store;
            try {
                store = KeyStore.getInstance(KeyStore.getDefaultType());
            } catch (KeyStoreException e) {
                throw new IllegalStateException("Java runtime specification requires support for JKS key store", e);
            }
            try {
                store.load(null, password);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Java runtime specification requires support for JKS key store", e);
            } catch (CertificateException e) {
                throw new IllegalStateException("Empty keystore", e);
            }
            KeyManagerFactory kmf;
            try {
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(
                        "Java runtime specification requires support for default key manager", e);
            }
            try {
                kmf.init(store, password);
            } catch (KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException e) {
                throw new IllegalStateException(e);
            }
            try {
                context.init(kmf.getKeyManagers(), new TrustManager[] {agentTrustManager}, null);
            } catch (KeyManagementException e) {
                throw new IllegalStateException(e);
            }
            // Create the protocols that will be attempted to connect to the controller.
            protocolsImpls = new JnlpProtocolHandlerFactory(this.executor)
                    .withIOHub(hub)
                    .withSSLContext(context)
                    .withPreferNonBlockingIO(false) // we only have one connection, prefer blocking I/O
                    .handlers();
        } catch (IOException e) {
            events.error(e);
        }
    }

    private JnlpEndpointResolver createEndpointResolver() {
        if (directConnection == null) {
            SSLSocketFactory sslSocketFactory = null;
            try {
                sslSocketFactory = getSSLSocketFactory(candidateCertificates, disableHttpsCertValidation);
            } catch (Exception e) {
                events.error(e);
            }
            return new JnlpAgentEndpointResolver(
                    candidateUrls.stream().map(URL::toExternalForm).collect(Collectors.toList()),
                    agentName,
                    credentials,
                    proxyCredentials,
                    tunnel,
                    sslSocketFactory,
                    noReconnect,
                    noReconnectAfter,
                    events);
        } else {
            return new JnlpAgentEndpointConfigurator(
                    directConnection, instanceIdentity, protocols, proxyCredentials, events);
        }
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
        final JnlpAgentEndpoint endpoint = RetryUtils.succeedsWithRetries(
                resolver::resolve,
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
        var negotiatedProtocols = protocolsImpls.stream()
                .filter(JnlpProtocolHandler::isEnabled)
                .filter(p -> endpoint.isProtocolSupported(p.getName()))
                .collect(Collectors.toSet());
        var serverProtocols = endpoint.getProtocols() == null ? "?" : String.join(",", endpoint.getProtocols());
        LOGGER.info("Protocols support: Server " + "[" + serverProtocols + "]"
                + ", Client " + "["
                + protocolsImpls.stream()
                        .map(p -> p.getName() + (!p.isEnabled() ? " (disabled)" : ""))
                        .collect(Collectors.joining(","))
                + "]"
                + ", Negociated: " + "["
                + negotiatedProtocols.stream().map(JnlpProtocolHandler::getName).collect(Collectors.joining(","))
                + "]");
        for (var protocol : negotiatedProtocols) {
            jnlpSocket = RetryUtils.succeedsWithRetries(
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
            jnlpSocket = null;
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

    @Override
    public Boolean waitForReady() throws InterruptedException {
        resolver.waitForReady();
        return true;
    }

    @Override
    public String getProtocol() {
        return protocolName;
    }

    @Override
    public void close() {
        if (jnlpSocket != null) {
            try {
                jnlpSocket.close();
            } catch (IOException e) {
                events.status("Failed to close socket", e);
            }
        }
        if (hub != null) {
            try {
                hub.close();
            } catch (IOException e) {
                events.status("Failed to close IOHub", e);
            }
        }
    }
}
