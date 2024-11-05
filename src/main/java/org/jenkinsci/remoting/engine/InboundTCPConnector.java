package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.Engine;
import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.cert.DelegatingX509ExtendedTrustManager;
import org.jenkinsci.remoting.protocol.cert.PublicKeyMatchingX509ExtendedTrustManager;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;
import org.jenkinsci.remoting.util.KeyUtils;
import org.jenkinsci.remoting.util.SSLUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Connects to a controller using inbound TCP.
 */
@Restricted(NoExternalUse.class)
public class InboundTCPConnector implements EndpointConnector {
    private static final Logger LOGGER = Logger.getLogger(InboundTCPConnector.class.getName());

    private final JnlpEndpointResolver jnlpEndpointResolver;
    private final List<URL> candidateUrls;
    private final DelegatingX509ExtendedTrustManager agentTrustManager;
    private final boolean keepAlive;
    private final EndpointConnectorData data;

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

    public InboundTCPConnector(
            EndpointConnectorData data,
            @NonNull List<URL> candidateUrls,
            @CheckForNull DelegatingX509ExtendedTrustManager agentTrustManager,
            boolean keepAlive,
            @NonNull JnlpEndpointResolver jnlpEndpointResolver) {
        this.data = data;
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
                    data.events().status("Remote identity confirmed: " + fingerprint);
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
            if (data.jarCache() != null) {
                bldr.withJarCache(data.jarCache());
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
        var hub = IOHub.create(data.executor());
        closeables.add(hub);
        var context = SSLUtils.createSSLContext(agentTrustManager);

        final JnlpAgentEndpoint endpoint = RetryUtils.succeedsWithRetries(
                jnlpEndpointResolver::resolve,
                data.noReconnectAfter(),
                data.events(),
                x -> "Could not locate server among " + candidateUrls + ": " + x.getMessage());
        if (endpoint == null) {
            data.events().status("Could not resolve server among " + this.candidateUrls);
            return null;
        }
        url = endpoint.getServiceUrl();

        data.events()
                .status(String.format(
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

        data.events().status("Handshaking");
        // must be read-write
        final Map<String, String> headers = new HashMap<>();
        headers.put(JnlpConnectionState.CLIENT_NAME_KEY, data.agentName());
        headers.put(JnlpConnectionState.SECRET_KEY, data.secretKey());
        // Create the protocols that will be attempted to connect to the controller.
        var clientProtocols = new JnlpProtocolHandlerFactory(data.executor())
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
                        data.events().status("Connecting to " + endpoint.describe() + " using " + protocol.getName());
                        // default is 30 mins. See PingThread for the ping interval
                        final Socket s = endpoint.open(Engine.SOCKET_TIMEOUT);
                        s.setKeepAlive(keepAlive);
                        return s;
                    },
                    data.noReconnectAfter(),
                    data.events());
            if (jnlpSocket == null) {
                return null;
            }
            closeables.add(jnlpSocket);
            try {
                protocolName = protocol.getName();
                return protocol.connect(
                        jnlpSocket, headers, new EngineJnlpConnectionStateListener(endpoint.getPublicKey(), headers));
            } catch (IOException ioe) {
                data.events().status("Protocol " + protocol.getName() + " failed to establish channel", ioe);
                protocolName = null;
            } catch (RuntimeException e) {
                data.events().status("Protocol " + protocol.getName() + " encountered a runtime error", e);
                protocolName = null;
            }
            // On failure form a new connection.
            jnlpSocket.close();
            closeables.remove(jnlpSocket);
        }
        if (negotiatedProtocols.isEmpty()) {
            data.events()
                    .status(
                            "reconnect rejected",
                            new Exception("The server rejected the connection: None of the protocols were accepted"));
        } else {
            data.events()
                    .status(
                            "reconnect rejected",
                            new Exception("The server rejected the connection: None of the protocols are enabled"));
        }
        return null;
    }

    @NonNull
    private static String buildDebugProtocolsMessage(
            String serverProtocols,
            List<JnlpProtocolHandler<? extends JnlpConnectionState>> clientProtocols,
            Set<JnlpProtocolHandler<? extends JnlpConnectionState>> negotiatedProtocols) {
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
                data.events().status("Failed to close resource " + c, e);
            }
        });
    }
}
