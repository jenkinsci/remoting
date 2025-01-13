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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.remoting.Engine;
import hudson.remoting.EngineListenerSplitter;
import hudson.remoting.Launcher;
import hudson.remoting.NoProxyEvaluator;
import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import org.jenkinsci.remoting.util.ThrowableUtils;
import org.jenkinsci.remoting.util.VersionNumber;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * @author Stephen Connolly
 * @since 3.0
 */
public class JnlpAgentEndpointResolver extends JnlpEndpointResolver {

    private static final Logger LOGGER = Logger.getLogger(JnlpAgentEndpointResolver.class.getName());

    @NonNull
    private final List<String> jenkinsUrls;

    private final String agentName;

    private String credentials;

    private String proxyCredentials;

    private String tunnel;

    private SSLSocketFactory sslSocketFactory;

    private boolean noReconnect;

    @NonNull
    private Duration noReconnectAfter;

    private EngineListenerSplitter events;

    private boolean first = true;

    /**
     * If specified, only the protocols from the list will be tried during the connection.
     * The option provides protocol names, but the order of the check is defined internally and cannot be changed.
     * This option can be also used in order to workaround issues when the headers cannot be delivered
     * from the server due to whatever reason (e.g. JENKINS-41730).
     */
    private static String PROTOCOL_NAMES_TO_TRY =
            System.getProperty(JnlpAgentEndpointResolver.class.getName() + ".protocolNamesToTry");

    public JnlpAgentEndpointResolver(
            @NonNull List<String> jenkinsUrls,
            String agentName,
            String credentials,
            String proxyCredentials,
            String tunnel,
            SSLSocketFactory sslSocketFactory,
            boolean noReconnect,
            @NonNull Duration noReconnectAfter,
            EngineListenerSplitter events) {
        this.jenkinsUrls = new ArrayList<>(jenkinsUrls);
        this.agentName = agentName;
        this.credentials = credentials;
        this.proxyCredentials = proxyCredentials;
        this.tunnel = tunnel;
        this.sslSocketFactory = sslSocketFactory;
        this.noReconnect = noReconnect;
        this.noReconnectAfter = noReconnectAfter;
        this.events = events;
    }

    @CheckForNull
    @Override
    public JnlpAgentEndpoint resolve() throws IOException {
        events.status("Locating server among " + this.jenkinsUrls);
        IOException firstError = null;
        for (String jenkinsUrl : jenkinsUrls) {
            if (jenkinsUrl == null) {
                continue;
            }

            final URL selectedJenkinsURL;
            final URL salURL;
            try {
                selectedJenkinsURL = new URL(jenkinsUrl);
                salURL = toAgentListenerURL(jenkinsUrl);
            } catch (MalformedURLException ex) {
                LOGGER.log(
                        Level.WARNING,
                        String.format("Cannot parse agent endpoint URL %s. Skipping it", jenkinsUrl),
                        ex);
                continue;
            }

            // find out the TCP port
            HttpURLConnection con = (HttpURLConnection)
                    openURLConnection(salURL, agentName, credentials, proxyCredentials, sslSocketFactory, null);
            try {
                try {
                    con.setConnectTimeout(30000);
                    con.setReadTimeout(60000);
                    con.connect();
                } catch (IOException x) {
                    firstError = ThrowableUtils.chain(
                            firstError, new IOException("Failed to connect to " + salURL + ": " + x.getMessage(), x));
                    continue;
                }
                if (con.getResponseCode() != 200) {
                    firstError = ThrowableUtils.chain(
                            firstError,
                            new IOException(
                                    salURL + " is invalid: " + con.getResponseCode() + " " + con.getResponseMessage()));
                    continue;
                }

                // Check if current version of agent is supported
                String minimumSupportedVersionHeader = con.getHeaderField(Engine.REMOTING_MINIMUM_VERSION_HEADER);
                if (minimumSupportedVersionHeader != null) {
                    VersionNumber minimumSupportedVersion = new VersionNumber(minimumSupportedVersionHeader);
                    VersionNumber currentVersion = new VersionNumber(Launcher.VERSION);
                    if (currentVersion.isOlderThan(minimumSupportedVersion)) {
                        firstError = ThrowableUtils.chain(
                                firstError,
                                new IOException("Agent version " + minimumSupportedVersion + " or newer is required."));
                        continue;
                    }
                }

                Set<String> agentProtocolNames = null;

                String portStr = Optional.ofNullable(con.getHeaderField("X-Jenkins-JNLP-Port"))
                        .orElse(con.getHeaderField("X-Hudson-JNLP-Port"));
                String host = Optional.ofNullable(con.getHeaderField("X-Jenkins-JNLP-Host"))
                        .orElse(salURL.getHost());
                String protocols = con.getHeaderField("X-Jenkins-Agent-Protocols");
                if (protocols != null) {
                    // Take the list of protocols to try from the headers
                    agentProtocolNames = Stream.of(protocols.split(","))
                            .map(String::trim)
                            .filter(Predicate.not(String::isEmpty))
                            .collect(Collectors.toSet());

                    if (agentProtocolNames.isEmpty()) {
                        LOGGER.log(
                                Level.WARNING,
                                "Received the empty list of supported protocols from the server. "
                                        + "All protocols are disabled on the controller side OR the 'X-Jenkins-Agent-Protocols' header is corrupted (JENKINS-41730). "
                                        + "In the case of the header corruption as a workaround you can use the "
                                        + "'org.jenkinsci.remoting.engine.JnlpAgentEndpointResolver.protocolNamesToTry' system property "
                                        + "to define the supported protocols.");
                    } else {
                        LOGGER.log(
                                Level.FINE, "Remoting server accepts the following protocols: " + agentProtocolNames);
                    }
                }

                if (PROTOCOL_NAMES_TO_TRY != null) {
                    // Take a list of protocols to try from the system property
                    LOGGER.log(
                            Level.INFO,
                            "Ignoring the list of supported remoting protocols provided by the server, because the "
                                    + "'org.jenkinsci.remoting.engine.JnlpAgentEndpointResolver.protocolNamesToTry' property is defined. Will try {0}",
                            PROTOCOL_NAMES_TO_TRY);
                    agentProtocolNames = Stream.of(PROTOCOL_NAMES_TO_TRY.split(","))
                            .map(String::trim)
                            .filter(Predicate.not(String::isEmpty))
                            .collect(Collectors.toSet());
                }

                String idHeader = con.getHeaderField("X-Instance-Identity");
                if (idHeader == null) {
                    firstError = ThrowableUtils.chain(
                            firstError, new IOException(jenkinsUrl + " is missing instance-identity plugin"));
                    continue;
                }
                RSAPublicKey identity;
                try {
                    identity = getIdentity(idHeader);
                    if (identity == null) {
                        firstError = ThrowableUtils.chain(
                                firstError,
                                new IOException(salURL + " appears to be publishing an invalid X-Instance-Identity."));
                        continue;
                    }
                } catch (InvalidKeySpecException e) {
                    firstError = ThrowableUtils.chain(
                            firstError,
                            new IOException(salURL + " appears to be publishing an invalid X-Instance-Identity."));
                    continue;
                }

                if (portStr == null) {
                    firstError = ThrowableUtils.chain(firstError, new IOException(jenkinsUrl + " is not Jenkins"));
                    continue;
                }
                int port;
                try {
                    port = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    firstError = ThrowableUtils.chain(
                            firstError, new IOException(jenkinsUrl + " is publishing an invalid port", e));
                    continue;
                }
                if (port <= 0 || 65536 <= port) {
                    firstError = ThrowableUtils.chain(
                            firstError, new IOException(jenkinsUrl + " is publishing an invalid port"));
                    continue;
                }
                if (tunnel == null) {
                    if (!isPortVisible(host, port)) {
                        firstError = ThrowableUtils.chain(
                                firstError,
                                new IOException(
                                        jenkinsUrl + " provided port:" + port + " is not reachable on host " + host));
                        continue;
                    } else {
                        LOGGER.log(Level.FINE, "TCP Agent Listener Port availability check passed");
                    }
                } else {
                    LOGGER.log(
                            Level.INFO,
                            "Remoting TCP connection tunneling is enabled. "
                                    + "Skipping the TCP Agent Listener Port availability check");
                }
                // sort the URLs so that the winner is the one we try first next time
                final String winningJenkinsUrl = jenkinsUrl;
                jenkinsUrls.sort((o1, o2) -> {
                    if (winningJenkinsUrl.equals(o1)) {
                        return -1;
                    }
                    if (winningJenkinsUrl.equals(o2)) {
                        return 1;
                    }
                    return 0;
                });
                if (tunnel != null) {
                    HostPort hostPort = new HostPort(tunnel, host, port);
                    host = hostPort.getHost();
                    port = hostPort.getPort();
                }

                // TODO: all the checks above do not make much sense if tunneling is enabled (JENKINS-52246)
                return new JnlpAgentEndpoint(
                        host, port, identity, agentProtocolNames, selectedJenkinsURL, proxyCredentials);
            } finally {
                con.disconnect();
            }
        }
        if (firstError != null) {
            throw firstError;
        }
        return null;
    }

    @SuppressFBWarnings(
            value = "UNENCRYPTED_SOCKET",
            justification = "This just verifies connection to the port. No data is transmitted.")
    private synchronized boolean isPortVisible(String hostname, int port) {
        boolean exitStatus = false;
        Socket s = null;

        Authenticator orig = null;
        try {
            if (proxyCredentials != null) {
                final int index = proxyCredentials.indexOf(':');
                if (index < 0) {
                    throw new IllegalArgumentException("Invalid credential");
                }
                orig = Authenticator.getDefault();
                // Using Authenticator.setDefault is a bit ugly, but there is no other way to control the authenticator
                // on the HTTPURLConnection object created internally by Socket.
                Authenticator.setDefault(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        if (getRequestorType().equals(RequestorType.PROXY)) {
                            return new PasswordAuthentication(
                                    proxyCredentials.substring(0, index),
                                    proxyCredentials.substring(index + 1).toCharArray());
                        }
                        return super.getPasswordAuthentication();
                    }
                });
            }
            InetSocketAddress proxyToUse = getResolvedHttpProxyAddress(hostname, port);
            s = proxyToUse == null ? new Socket() : new Socket(new Proxy(Proxy.Type.HTTP, proxyToUse));
            s.setReuseAddress(true);
            SocketAddress sa = new InetSocketAddress(hostname, port);
            s.connect(sa, 5000);
        } catch (IOException e) {
            LOGGER.warning(e.getMessage());
        } finally {
            if (s != null) {
                if (s.isConnected()) {
                    exitStatus = true;
                }
                try {
                    s.close();
                } catch (IOException e) {
                    LOGGER.warning(e.getMessage());
                }
            }
            if (orig != null) {
                Authenticator.setDefault(orig);
            }
        }
        return exitStatus;
    }

    @NonNull
    private URL toAgentListenerURL(@NonNull String jenkinsUrl) throws MalformedURLException {
        return new URL(jenkinsUrl + "tcpSlaveAgentListener/");
    }

    @Override
    public void waitForReady() throws InterruptedException {
        if (RetryUtils.succeedsWithRetries(
                        this::ping,
                        first && noReconnect ? Duration.ZERO : noReconnectAfter,
                        events,
                        x -> "Could not locate server among " + jenkinsUrls + ": " + x.getMessage())
                == null) {
            throw new RuntimeException("Could not locate server among " + jenkinsUrls);
        }
        first = false;
    }

    @CheckForNull
    static InetSocketAddress getResolvedHttpProxyAddress(@NonNull String host, int port) throws IOException {
        InetSocketAddress targetAddress = null;
        URI uri;
        try {
            uri = new URI("http", null, host, port, null, null, null);
        } catch (URISyntaxException x) {
            throw new IOException(x);
        }
        Iterator<Proxy> proxies = ProxySelector.getDefault().select(uri).iterator();
        while (targetAddress == null && proxies.hasNext()) {
            Proxy proxy = proxies.next();
            if (proxy.type() == Proxy.Type.DIRECT) {
                // Proxy.NO_PROXY with a DIRECT type is returned in two cases:
                // - when no proxy (none) has been configured in the JVM (either with system properties or by the
                // operating system)
                // - when the host URI is part of the exclusion list defined by system property -Dhttp.nonProxyHosts
                //
                // Unfortunately, the Proxy class does not provide a way to differentiate both cases to fallback to
                // environment variables only when no proxy has been configured. Therefore, we have to recheck if the
                // URI
                // host is in the exclusion list.
                //
                // Warning:
                //      This code only supports Java 9+ implementation where nonProxyHosts entries are not interpreted
                // as regex expressions anymore.
                //      Wildcard at the beginning or the end of an expression are the only remaining supported
                // behaviours (e.g. *.jenkins.io or 127.*)
                //      https://bugs.java.com/view_bug.do?bug_id=8035158
                //      http://hg.openjdk.java.net/jdk9/jdk9/jdk/rev/50a749f2cade
                String nonProxyHosts = System.getProperty("http.nonProxyHosts");
                if (nonProxyHosts != null && nonProxyHosts.length() != 0) {
                    // Build a list of regexps matching all nonProxyHosts entries
                    StringJoiner sj = new StringJoiner("|");
                    nonProxyHosts = nonProxyHosts.toLowerCase(Locale.ENGLISH);
                    for (String entry : nonProxyHosts.split("\\|")) {
                        if (entry.isEmpty()) {
                            continue;
                        } else if (entry.startsWith("*")) {
                            sj.add(".*" + Pattern.quote(entry.substring(1)));
                        } else if (entry.endsWith("*")) {
                            sj.add(Pattern.quote(entry.substring(0, entry.length() - 1)) + ".*");
                        } else {
                            sj.add(Pattern.quote(entry));
                        }
                        // Detect when the pattern contains multiple wildcard, which used to work previous to Java 9
                        // (e.g. 127.*.*.*)
                        if (entry.split("\\*").length > 2) {
                            LOGGER.log(
                                    Level.WARNING,
                                    "Using more than one wildcard is not supported in nonProxyHosts entries: {0}",
                                    entry);
                        }
                    }
                    Pattern nonProxyRegexps = Pattern.compile(sj.toString());
                    if (nonProxyRegexps
                            .matcher(host.toLowerCase(Locale.ENGLISH))
                            .matches()) {
                        return null;
                    } else {
                        break;
                    }
                }
            }
            if (proxy.type() == Proxy.Type.HTTP) {
                final SocketAddress address = proxy.address();
                if (!(address instanceof InetSocketAddress)) {
                    LOGGER.log(
                            Level.WARNING,
                            "Unsupported proxy address type {0}",
                            (address != null ? address.getClass() : "null"));
                    continue;
                }
                InetSocketAddress proxyAddress = (InetSocketAddress) address;
                if (proxyAddress.isUnresolved()) {
                    proxyAddress = new InetSocketAddress(proxyAddress.getHostName(), proxyAddress.getPort());
                }
                targetAddress = proxyAddress;
            }
        }
        if (targetAddress == null) {
            String httpProxy = System.getenv("http_proxy");
            if (httpProxy != null && NoProxyEvaluator.shouldProxy(host)) {
                try {
                    URL url = new URL(httpProxy);
                    targetAddress = new InetSocketAddress(url.getHost(), url.getPort());
                } catch (MalformedURLException e) {
                    LOGGER.log(Level.WARNING, "Not using http_proxy environment variable which is invalid.", e);
                }
            }
        }
        return targetAddress;
    }

    /**
     * Gets URL connection.
     * If http_proxy environment variable exists,  the connection uses the proxy.
     * Credentials can be passed e.g. to support running Jenkins behind a (reverse) proxy requiring authorization
     * FIXME: similar to hudson.remoting.Util.openURLConnection which is still used in hudson.remoting.Launcher
     */
    @Restricted(NoExternalUse.class)
    @SuppressFBWarnings(
            value = "URLCONNECTION_SSRF_FD",
            justification = "Used by the agent for retrieving connection info from the server.")
    public static URLConnection openURLConnection(
            URL url,
            @CheckForNull String agentName,
            @CheckForNull String credentials,
            @CheckForNull String proxyCredentials,
            @CheckForNull SSLSocketFactory sslSocketFactory,
            @CheckForNull HostnameVerifier hostnameVerifier)
            throws IOException {
        String httpProxy = null;
        // If http.proxyHost property exists, openConnection() uses it.
        if (System.getProperty("http.proxyHost") == null) {
            httpProxy = System.getenv("http_proxy");
        }
        URLConnection con;
        if (httpProxy != null && "http".equals(url.getProtocol()) && NoProxyEvaluator.shouldProxy(url.getHost())) {
            try {
                URL proxyUrl = new URL(httpProxy);
                SocketAddress addr = new InetSocketAddress(proxyUrl.getHost(), proxyUrl.getPort());
                Proxy proxy = new Proxy(Proxy.Type.HTTP, addr);
                con = url.openConnection(proxy);
            } catch (MalformedURLException e) {
                LOGGER.log(Level.WARNING, "Not using http_proxy environment variable which is invalid.", e);
                con = url.openConnection();
            }
        } else {
            con = url.openConnection();
        }
        if (agentName != null) {
            con.setRequestProperty(JnlpConnectionState.CLIENT_NAME_KEY, agentName);
        }
        if (credentials != null) {
            String encoding = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            con.setRequestProperty("Authorization", "Basic " + encoding);
        }
        if (proxyCredentials != null) {
            String encoding = Base64.getEncoder().encodeToString(proxyCredentials.getBytes(StandardCharsets.UTF_8));
            con.setRequestProperty("Proxy-Authorization", "Basic " + encoding);
        }
        if (con instanceof HttpsURLConnection) {
            final HttpsURLConnection httpsConnection = (HttpsURLConnection) con;
            if (sslSocketFactory != null) {
                httpsConnection.setSSLSocketFactory(sslSocketFactory);
            }
            if (hostnameVerifier != null) {
                httpsConnection.setHostnameVerifier(hostnameVerifier);
            }
        }
        return con;
    }

    @SuppressFBWarnings(value = "NP_BOOLEAN_RETURN_NULL", justification = "null is used to indicate no connection")
    private Boolean ping() throws IOException {
        for (String jenkinsUrl : jenkinsUrls) {
            URL url = toAgentListenerURL(jenkinsUrl);
            HttpURLConnection con = (HttpURLConnection)
                    openURLConnection(url, agentName, credentials, proxyCredentials, sslSocketFactory, null);
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            con.connect();
            if (con.getResponseCode() == 200) {
                return true;
            } else if (con.getResponseCode() == 404) {
                events.status("Controller isn't ready to talk to us on " + url
                        + ". Maybe TCP port for inbound agents is disabled?");
            } else {
                events.status("Controller isn't ready to talk to us on " + url + ". Will try again: response code="
                        + con.getResponseCode());
            }
        }
        return null;
    }
}
