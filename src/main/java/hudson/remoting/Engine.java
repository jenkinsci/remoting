/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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
package hudson.remoting;

import static org.jenkinsci.remoting.util.SSLUtils.getSSLSocketFactory;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;
import net.jcip.annotations.NotThreadSafe;
import org.jenkinsci.remoting.engine.EndpointConnector;
import org.jenkinsci.remoting.engine.EndpointConnectorData;
import org.jenkinsci.remoting.engine.InboundTCPConnector;
import org.jenkinsci.remoting.engine.JnlpAgentEndpointConfigurator;
import org.jenkinsci.remoting.engine.JnlpAgentEndpointResolver;
import org.jenkinsci.remoting.engine.JnlpEndpointResolver;
import org.jenkinsci.remoting.engine.WebSocketConnector;
import org.jenkinsci.remoting.engine.WorkDirManager;
import org.jenkinsci.remoting.protocol.cert.BlindTrustX509ExtendedTrustManager;
import org.jenkinsci.remoting.protocol.cert.DelegatingX509ExtendedTrustManager;
import org.jenkinsci.remoting.util.https.NoCheckHostnameVerifier;

/**
 * Agent engine that proactively connects to Jenkins controller.
 *
 * @author Kohsuke Kawaguchi
 */
@NotThreadSafe // the fields in this class should not be modified by multiple threads concurrently
public class Engine extends Thread {

    /**
     * HTTP header sent by Jenkins to indicate the earliest version of Remoting it is prepared to accept connections from.
     */
    public static final String REMOTING_MINIMUM_VERSION_HEADER = "X-Remoting-Minimum-Version";

    /**
     * The header name to be used for the connection cookie when using websockets.
     */
    public static final String WEBSOCKET_COOKIE_HEADER = "Connection-Cookie";

    /**
     * Thread pool that sets {@link #CURRENT}.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();

        @Override
        public Thread newThread(@NonNull final Runnable r) {
            Thread thread = defaultFactory.newThread(() -> {
                CURRENT.set(Engine.this);
                r.run();
            });
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler(
                    (t, e) -> LOGGER.log(Level.SEVERE, e, () -> "Uncaught exception in thread " + t));
            return thread;
        }
    });

    /**
     * @deprecated
     *      Use {@link #events}.
     */
    @Deprecated
    public final EngineListener listener;

    private final EngineListenerSplitter events = new EngineListenerSplitter();
    /**
     * To make Jenkins more graceful against user error,
     * JNLP agent can try to connect to multiple possible Jenkins URLs.
     * This field specifies those candidate URLs, such as
     * "http://foo.bar/jenkins/".
     */
    private final List<URL> candidateUrls;
    /**
     * The list of {@link X509Certificate} instances to trust when connecting to any of the {@link #candidateUrls}
     * or {@code null} to use the JVM default trust store.
     */
    private List<X509Certificate> candidateCertificates;

    /**
     * URL that points to Jenkins's tcp agent listener, like {@code http://myhost/hudson/}
     *
     * <p>
     * This value is determined from {@link #candidateUrls} after a successful connection.
     * Note that this URL <b>DOES NOT</b> have "tcpSlaveAgentListener" in it.
     */
    @CheckForNull
    private URL hudsonUrl;

    private final String secretKey;
    private final String agentName;
    private boolean webSocket;
    private Map<String, String> webSocketHeaders;
    private String credentials;
    private String protocolName;
    private String proxyCredentials;

    /**
     * See {@link Launcher#tunnel} for the documentation.
     */
    @CheckForNull
    private String tunnel;

    private boolean disableHttpsCertValidation = false;

    @CheckForNull
    private HostnameVerifier hostnameVerifier;

    private boolean noReconnect = false;

    @NonNull
    private Duration noReconnectAfter = Duration.ofDays(10);

    /**
     * Determines whether the socket will have {@link Socket#setKeepAlive(boolean)} set or not.
     *
     * @since 2.62.1
     */
    private boolean keepAlive = true;

    @CheckForNull
    private JarCache jarCache = null;

    /**
     * Specifies a destination for the agent log.
     * If specified, this option overrides the default destination within {@link #workDir}.
     * If both this options and {@link #workDir} is not set, the log will not be generated.
     * @since 3.8
     */
    @CheckForNull
    private Path agentLog;

    /**
     * Specified location of the property file with JUL settings.
     * @since 3.8
     */
    @CheckForNull
    private Path loggingConfigFilePath = null;

    /**
     * Specifies a default working directory of the remoting instance.
     * If specified, this directory will be used to store logs, JAR cache, etc.
     * <p>
     * In order to retain compatibility, the option is disabled by default.
     * <p>
     * Jenkins specifics: This working directory is expected to be equal to the agent root specified in Jenkins configuration.
     * @since 3.8
     */
    @CheckForNull
    public Path workDir = null;

    /**
     * Specifies a directory within {@link #workDir}, which stores all the remoting-internal files.
     * <p>
     * This option is not expected to be used frequently, but it allows remoting users to specify a custom
     * storage directory if the default {@code remoting} directory is consumed by other stuff.
     * @since 3.8
     */
    @NonNull
    public String internalDir = WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation();

    /**
     * Fail the initialization if the workDir or internalDir are missing.
     * This option presumes that the workspace structure gets initialized previously in order to ensure that we do not start up with a borked instance
     * (e.g. if a filesystem mount gets disconnected).
     * @since 3.8
     */
    public boolean failIfWorkDirIsMissing = WorkDirManager.DEFAULT_FAIL_IF_WORKDIR_IS_MISSING;

    private final DelegatingX509ExtendedTrustManager agentTrustManager =
            new DelegatingX509ExtendedTrustManager(new BlindTrustX509ExtendedTrustManager());

    private final String directConnection;
    private final String instanceIdentity;
    private final Set<String> protocols;

    public Engine(EngineListener listener, List<URL> hudsonUrls, String secretKey, String agentName) {
        this(listener, hudsonUrls, secretKey, agentName, null, null, null);
    }

    public Engine(
            EngineListener listener,
            List<URL> urls,
            String secretKey,
            String agentName,
            String directConnection,
            String instanceIdentity,
            Set<String> protocols) {
        this.listener = listener;
        this.directConnection = directConnection;
        this.events.add(listener);
        this.candidateUrls = urls.stream().map(Engine::ensureTrailingSlash).collect(Collectors.toList());
        this.secretKey = secretKey;
        this.agentName = agentName;
        this.instanceIdentity = instanceIdentity;
        this.protocols = protocols;
        if (candidateUrls.isEmpty() && instanceIdentity == null) {
            throw new IllegalArgumentException("No URLs given");
        }
        setUncaughtExceptionHandler((t, e) -> {
            LOGGER.log(Level.SEVERE, e, () -> "Uncaught exception in Engine thread " + t);
            interrupt();
        });
    }

    private static URL ensureTrailingSlash(URL u) {
        if (u.toString().endsWith("/")) {
            return u;
        } else {
            try {
                return new URL(u + "/");
            } catch (MalformedURLException x) {
                throw new IllegalArgumentException(x);
            }
        }
    }

    /**
     * Starts the engine.
     * The procedure initializes the working directory and all the required environment
     * @throws IOException Initialization error
     * @since 3.9
     */
    public synchronized void startEngine() throws IOException {
        startEngine(false);
    }

    /**
     * Starts engine.
     * @param dryRun If {@code true}, do not actually start the engine.
     *               This method can be used for testing startup logic.
     */
    /*package*/ void startEngine(boolean dryRun) throws IOException {
        LOGGER.log(Level.INFO, "Using Remoting version: {0}", Launcher.VERSION);
        @CheckForNull File jarCacheDirectory = null;

        // Prepare the working directory if required
        if (workDir != null) {
            final WorkDirManager workDirManager = WorkDirManager.getInstance();
            if (jarCache != null) {
                // Somebody has already specificed Jar Cache, hence we do not need it in the workspace.
                workDirManager.disable(WorkDirManager.DirType.JAR_CACHE_DIR);
            }

            if (loggingConfigFilePath != null) {
                workDirManager.setLoggingConfig(loggingConfigFilePath.toFile());
            }

            final Path path = workDirManager.initializeWorkDir(workDir.toFile(), internalDir, failIfWorkDirIsMissing);
            jarCacheDirectory = workDirManager.getLocation(WorkDirManager.DirType.JAR_CACHE_DIR);
            workDirManager.setupLogging(path, agentLog);
        } else if (jarCache == null) {
            LOGGER.log(
                    Level.WARNING,
                    "No Working Directory. Using the legacy JAR Cache location: {0}",
                    JarCache.DEFAULT_NOWS_JAR_CACHE_LOCATION);
            jarCacheDirectory = JarCache.DEFAULT_NOWS_JAR_CACHE_LOCATION;
        }

        if (jarCache == null) {
            if (jarCacheDirectory == null) {
                // Should never happen in the current code
                throw new IOException("Cannot find the JAR Cache location");
            }
            LOGGER.log(Level.FINE, "Using standard File System JAR Cache. Root Directory is {0}", jarCacheDirectory);
            try {
                jarCache = new FileSystemJarCache(jarCacheDirectory, true);
            } catch (IllegalArgumentException ex) {
                throw new IOException("Failed to initialize FileSystem JAR Cache in " + jarCacheDirectory, ex);
            }
        } else {
            LOGGER.log(Level.INFO, "Using custom JAR Cache: {0}", jarCache);
        }

        // Start the engine thread
        if (!dryRun) {
            this.start();
        }
    }

    /**
     * Configures custom JAR Cache location.
     * This option disables JAR Caching in the working directory.
     * @param jarCache JAR Cache to be used
     * @since 2.24
     */
    public void setJarCache(@NonNull JarCache jarCache) {
        this.jarCache = jarCache;
    }

    /**
     * Sets path to the property file with JUL settings.
     * @param filePath JAR Cache to be used
     * @since 3.8
     */
    public void setLoggingConfigFile(@NonNull Path filePath) {
        this.loggingConfigFilePath = filePath;
    }

    /**
     * Provides Jenkins URL if available.
     * @return Jenkins URL. May return {@code null} if the connection is not established or if the URL cannot be determined
     *         in the {@link JnlpAgentEndpointResolver}.
     */
    @CheckForNull
    public URL getHudsonUrl() {
        return hudsonUrl;
    }

    public void setWebSocket(boolean webSocket) {
        this.webSocket = webSocket;
    }

    /**
     * Sets map of custom websocket headers. These headers will be applied to the websocket connection to Jenkins.
     *
     * @param webSocketHeaders a map of the headers to apply to the websocket connection
     */
    public void setWebSocketHeaders(@NonNull Map<String, String> webSocketHeaders) {
        this.webSocketHeaders = webSocketHeaders;
    }

    /**
     * If set, connect to the specified host and port instead of connecting directly to Jenkins.
     * @param tunnel Value. {@code null} to disable tunneling
     */
    public void setTunnel(@CheckForNull String tunnel) {
        this.tunnel = tunnel;
    }

    public void setCredentials(String creds) {
        this.credentials = creds;
    }

    public void setProxyCredentials(String proxyCredentials) {
        this.proxyCredentials = proxyCredentials;
    }

    public void setNoReconnect(boolean noReconnect) {
        this.noReconnect = noReconnect;
    }

    public void setNoReconnectAfter(@NonNull Duration noReconnectAfter) {
        this.noReconnectAfter = noReconnectAfter;
    }

    /**
     * Determines if JNLPAgentEndpointResolver will not perform certificate validation in the HTTPs mode.
     *
     * @return {@code true} if the certificate validation is disabled.
     */
    public boolean isDisableHttpsCertValidation() {
        return disableHttpsCertValidation;
    }

    /**
     * Sets if JNLPAgentEndpointResolver will not perform certificate validation in the HTTPs mode.
     *
     * @param disableHttpsCertValidation {@code true} if the certificate validation is disabled.
     */
    public void setDisableHttpsCertValidation(boolean disableHttpsCertValidation) {
        this.disableHttpsCertValidation = disableHttpsCertValidation;
        if (disableHttpsCertValidation) {
            this.hostnameVerifier = new NoCheckHostnameVerifier();
        } else {
            this.hostnameVerifier = null;
        }
    }

    /**
     * Sets the destination for agent logs.
     * @param agentLog Path to the agent log.
     *      If {@code null}, the engine will pick the default behavior depending on the {@link #workDir} value
     * @since 3.8
     */
    public void setAgentLog(@CheckForNull Path agentLog) {
        this.agentLog = agentLog;
    }

    /**
     * Specified a path to the work directory.
     * @param workDir Path to the working directory of the remoting instance.
     *                {@code null} Disables the working directory.
     * @since 3.8
     */
    public void setWorkDir(@CheckForNull Path workDir) {
        this.workDir = workDir;
    }

    /**
     * Specifies name of the internal data directory within {@link #workDir}.
     * @param internalDir Directory name
     * @since 3.8
     */
    public void setInternalDir(@NonNull String internalDir) {
        this.internalDir = internalDir;
    }

    /**
     * Sets up behavior if the workDir or internalDir are missing during the startup.
     * This option presumes that the workspace structure gets initialized previously in order to ensure that we do not start up with a borked instance
     * (e.g. if a filesystem mount gets disconnected).
     * @param failIfWorkDirIsMissing Flag
     * @since 3.8
     */
    public void setFailIfWorkDirIsMissing(boolean failIfWorkDirIsMissing) {
        this.failIfWorkDirIsMissing = failIfWorkDirIsMissing;
    }

    /**
     * Returns {@code true} if and only if the socket to the controller will have {@link Socket#setKeepAlive(boolean)} set.
     *
     * @return {@code true} if and only if the socket to the controller will have {@link Socket#setKeepAlive(boolean)} set.
     */
    public boolean isKeepAlive() {
        return keepAlive;
    }

    /**
     * Sets the {@link Socket#setKeepAlive(boolean)} to use for the connection to the controller.
     *
     * @param keepAlive the {@link Socket#setKeepAlive(boolean)} to use for the connection to the controller.
     */
    public void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    public void setCandidateCertificates(List<X509Certificate> candidateCertificates) {
        this.candidateCertificates = candidateCertificates == null ? null : new ArrayList<>(candidateCertificates);
    }

    public void addCandidateCertificate(X509Certificate certificate) {
        if (candidateCertificates == null) {
            candidateCertificates = new ArrayList<>();
        }
        candidateCertificates.add(certificate);
    }

    public void addListener(EngineListener el) {
        events.add(el);
    }

    public void removeListener(EngineListener el) {
        events.remove(el);
    }

    @Override
    @SuppressFBWarnings(value = "REC_CATCH_EXCEPTION", justification = "We need to catch all exceptions")
    public void run() {
        try (var connector = getEndpointConnector()) {
            while (true) {
                if (connector.waitUntilReady() == null) {
                    break;
                }
                var channelFuture = connector.connect();
                if (channelFuture == null) {
                    break;
                }
                var channel = channelFuture.get();
                this.protocolName = connector.getProtocol();
                this.hudsonUrl = connector.getUrl();
                events.status("Connected");
                channel.join();
                events.status("Terminated");
                if (noReconnect) {
                    break;
                }
                events.onDisconnect();
                reconnect();
            }
        } catch (Exception e) {
            events.error(e);
        }
    }

    private JnlpEndpointResolver createJnlpEndpointResolver() {
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

    private EndpointConnector getEndpointConnector() {
        var data = new EndpointConnectorData(
                agentName,
                secretKey,
                executor,
                events,
                noReconnectAfter,
                candidateCertificates,
                disableHttpsCertValidation,
                jarCache,
                proxyCredentials);
        if (webSocket) {
            return new WebSocketConnector(data, candidateUrls.get(0), webSocketHeaders, hostnameVerifier);
        } else {
            var jnlpEndpointResolver = createJnlpEndpointResolver();
            return new InboundTCPConnector(data, candidateUrls, agentTrustManager, keepAlive, jnlpEndpointResolver);
        }
    }

    private void reconnect() {
        try {
            events.status("Performing onReconnect operation.");
            events.onReconnect();
            events.status("onReconnect operation completed.");
        } catch (NoClassDefFoundError e) {
            events.status("onReconnect operation failed.");
            LOGGER.log(Level.WARNING, "Reconnection error.", e);
        }
    }

    /**
     * When invoked from within remoted {@link Callable} (that is,
     * from the thread that carries out the remote requests),
     * this method returns the {@link Engine} in which the remote operations
     * run.
     */
    public static Engine current() {
        return CURRENT.get();
    }

    private static final ThreadLocal<Engine> CURRENT = new ThreadLocal<>();

    private static final Logger LOGGER = Logger.getLogger(Engine.class.getName());

    /**
     * Socket read timeout.
     * A {@link SocketInputStream#read()} call associated with underlying Socket will block for only this amount of time
     * @since 2.4
     */
    public static final int SOCKET_TIMEOUT =
            Integer.getInteger(Engine.class.getName() + ".socketTimeout", 30 * 60 * 1000);

    /**
     * Get the agent name associated with this Engine instance.
     *
     * @return the agent name.
     * @since TODO
     */
    public String getAgentName() {
        // This is used by various external components that need to get the name from the engine.
        return agentName;
    }

    /**
     * Get the name of the communication protocol used in this Engine instance.
     * When the channel is not established by Engine (that is, {@link Engine#current()}) returns null),
     * use {@link Launcher#getCommunicationProtocolName()} instead.
     *
     * @return the communication protocol name.
     * @since 4.8
     */
    public String getProtocolName() {
        return this.protocolName;
    }
}
