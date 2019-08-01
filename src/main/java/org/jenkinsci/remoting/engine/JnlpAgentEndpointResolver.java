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

import hudson.remoting.Base64;
import hudson.remoting.Util;

import org.jenkinsci.remoting.util.https.NoCheckHostnameVerifier;
import org.jenkinsci.remoting.util.https.NoCheckTrustManager;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

import static java.util.logging.Level.INFO;
import static org.jenkinsci.remoting.util.ThrowableUtils.chain;

/**
 * @author Stephen Connolly
 * @since 3.0
 */
public class JnlpAgentEndpointResolver {

    private static final Logger LOGGER = Logger.getLogger(JnlpAgentEndpointResolver.class.getName());

    @Nonnull
    private final List<String> jenkinsUrls;

    private SSLSocketFactory sslSocketFactory;

    private String credentials;

    private String proxyCredentials;

    private String tunnel;

    private boolean disableHttpsCertValidation;

    /**
     * The base64 encoded byte array of the <a href="https://wiki.jenkins.io/display/JENKINS/Instance+Identity">Instance Identities</a> public key of the Jenkins master.<br>
     * This parameter is only required if the master does not expose an http(s) port.
     */
    private String instanceIdentity;

    /**
     * Indicates that the target server is headless and offers no TcpAgentListener.
     */
    private boolean disableHttpEndpointCheck;

    /**
     * If specified, only the protocols from the list will be tried during the connection.
     * The option provides protocol names, but the order of the check is defined internally and cannot be changed.
     * This option can be also used in order to workaround issues when the headers cannot be delivered
     * from the server due to whatever reason (e.g. JENKINS-41730).
     */
    private static String PROTOCOL_NAMES_TO_TRY =
            System.getProperty(JnlpAgentEndpointResolver.class.getName() + ".protocolNamesToTry");
    
    public JnlpAgentEndpointResolver(String... jenkinsUrls) {
        this.jenkinsUrls = new ArrayList<String>(Arrays.asList(jenkinsUrls));
    }

    public JnlpAgentEndpointResolver(@Nonnull List<String> jenkinsUrls) {
        this.jenkinsUrls = new ArrayList<String>(jenkinsUrls);
    }

    public SSLSocketFactory getSslSocketFactory() {
        return sslSocketFactory;
    }

    public void setSslSocketFactory(SSLSocketFactory sslSocketFactory) {
        this.sslSocketFactory = sslSocketFactory;
    }

    public String getCredentials() {
        return credentials;
    }

    public void setCredentials(String credentials) {
        this.credentials = credentials;
    }

    public void setCredentials(String user, String pass) {
        this.credentials = user + ":" + pass;
    }

    public String getProxyCredentials() {
        return proxyCredentials;
    }

    public void setProxyCredentials(String proxyCredentials) {
        this.proxyCredentials = proxyCredentials;
    }

    public void setProxyCredentials(String user, String pass) {
        this.proxyCredentials = user + ":" + pass;
    }

    @CheckForNull
    public String getTunnel() {
        return tunnel;
    }

    /**
     * Sets whether the target server is headless.
     * @param disableHttpEndpointCheck {@code true} if the target master is headless and hence has no Tcp Agent Listener endpoint.
     */
    public void setDisableHttpEndpointCheck(boolean disableHttpEndpointCheck) {
        this.disableHttpEndpointCheck = disableHttpEndpointCheck;
    }

    /**
     * Checks if the target server is headless and hence has no Tcp Agent Listener endpoint.
     * @since TODO
     */
    public boolean isDisableHttpEndpointCheck() {
        return disableHttpEndpointCheck;
    }

    public void setTunnel(@CheckForNull String tunnel) {
        this.tunnel = tunnel;
    }

    /**
     * This parameter is only required if the master does not expose an http(s) port. 
     * @param instanceIdentity The base64 encoded byte array of the <a href="https://wiki.jenkins.io/display/JENKINS/Instance+Identity">Instance Identities</a> public key of the Jenkins master.
     * @see <a href="https://wiki.jenkins.io/display/JENKINS/Instance+Identity">Instance Identity</a>
     * @see #setDisableHttpEndpointCheck
     * @since TODO
     */
    public void setInstanceIdentity(@CheckForNull String instanceIdentity) {
        this.instanceIdentity = instanceIdentity;
    }

    /**
     *  Determine if certificate checking should be ignored for JNLP endpoint
     *
     * @return {@code true} if the HTTPs certificate is disabled, endpoint check is ignored
     */

    public boolean isDisableHttpsCertValidation() {
        return disableHttpsCertValidation;
    }

    /**
     * Sets if the HTTPs certificate check should be disabled.
     *
     * This behavior is no recommended.
     * @param disableHttpsCertValidation
     * @since TODO
     */
    public void setDisableHttpsCertValidation(boolean disableHttpsCertValidation) {
        this.disableHttpsCertValidation = disableHttpsCertValidation;
    }

    @CheckForNull
    public JnlpAgentEndpoint resolve() throws IOException {
        IOException firstError = null;

        if (disableHttpEndpointCheck) {
            return getJnlpAgentEndpointFromConfig();
        }

        // Mode with TCP Agent Listener
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
                LOGGER.log(Level.WARNING, String.format("Cannot parse agent endpoint URL %s. Skipping it", jenkinsUrl), ex);
                continue;
            }

            // find out the TCP port
            HttpURLConnection con =
                    (HttpURLConnection) openURLConnection(salURL, credentials, proxyCredentials, sslSocketFactory, disableHttpsCertValidation);
            try {
                try {
                    con.setConnectTimeout(30000);
                    con.setReadTimeout(60000);
                    con.connect();
                } catch (IOException x) {
                    firstError = chain(firstError,
                            new IOException("Failed to connect to " + salURL + ": " + x.getMessage(), x));
                    continue;
                }
                if (con.getResponseCode() != 200) {
                    firstError = chain(firstError, new IOException(
                            salURL + " is invalid: " + con.getResponseCode() + " " + con.getResponseMessage()));
                    continue;
                }
                String host;
                String portStr;
                Set<String> agentProtocolNames = null;

                portStr = first(header(con, "X-Jenkins-JNLP-Port", "X-Hudson-JNLP-Port"));
                host = defaultString(first(header(con, "X-Jenkins-JNLP-Host")), salURL.getHost());
                List<String> protocols = header(con, "X-Jenkins-Agent-Protocols");
                if (protocols != null) {
                    // Take the list of protocols to try from the headers
                    agentProtocolNames = new HashSet<String>();
                    for (String names : protocols) {
                        for (String name : names.split(",")) {
                            name = name.trim();
                            if (!name.isEmpty()) {
                                agentProtocolNames.add(name);
                            }
                        }
                    }
                    
                    if (agentProtocolNames.isEmpty()) {
                        LOGGER.log(Level.WARNING, "Received the empty list of supported protocols from the server. " +
                                "All protocols are disabled on the master side OR the 'X-Jenkins-Agent-Protocols' header is corrupted (JENKINS-41730). " +
                                "In the case of the header corruption as a workaround you can use the " +
                                "'org.jenkinsci.remoting.engine.JnlpAgentEndpointResolver.protocolNamesToTry' system property " +
                                "to define the supported protocols.");
                    } else {
                        LOGGER.log(Level.INFO, "Remoting server accepts the following protocols: {0}", agentProtocolNames);
                    }
                }
                
                if (PROTOCOL_NAMES_TO_TRY != null) {
                    // Take a list of protocols to try from the system property
                    agentProtocolNames = new HashSet<String>();
                    LOGGER.log(Level.INFO, "Ignoring the list of supported remoting protocols provided by the server, because the " +
                        "'org.jenkinsci.remoting.engine.JnlpAgentEndpointResolver.protocolNamesToTry' property is defined. Will try {0}", PROTOCOL_NAMES_TO_TRY);
                    for (String name : PROTOCOL_NAMES_TO_TRY.split(",")) {
                        name = name.trim();
                        if (!name.isEmpty()) {
                            agentProtocolNames.add(name);
                        } 
                    }
                }

                String idHeader = first(header(con, "X-Instance-Identity"));
                RSAPublicKey identity;
                try {
                    identity = getIdentity(idHeader);
                    if (identity == null) {
                        firstError = chain(firstError, new IOException(
                                salURL + " appears to be publishing an invalid X-Instance-Identity."));
                        continue;
                    }
                } catch (InvalidKeySpecException e) {
                    firstError = chain(firstError, new IOException(
                            salURL + " appears to be publishing an invalid X-Instance-Identity."));
                    continue;
                }

                if (portStr == null) {
                    firstError = chain(firstError, new IOException(jenkinsUrl + " is not Jenkins"));
                    continue;
                }
                int port = 0;
                try {
                    port = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    firstError = chain(firstError, new IOException(jenkinsUrl + " is publishing an invalid port", e));
                    continue;
                }
                if (port <= 0 || 65536 <= port) {
                    firstError = chain(firstError, new IOException(jenkinsUrl + " is publishing an invalid port"));
                    continue;
                }
                if (tunnel == null) {
                    if (!isPortVisible(host, port, 5000)) {
                        firstError = chain(firstError, new IOException(jenkinsUrl + " provided port:" + port
                                + " is not reachable"));
                        continue;
                    } else {
                        LOGGER.log(Level.FINE, "TCP Agent Listener Port availability check passed");
                    }
                } else {
                    LOGGER.log(Level.INFO, "Remoting TCP connection tunneling is enabled. " +
                            "Skipping the TCP Agent Listener Port availability check");
                }
                // sort the URLs so that the winner is the one we try first next time
                final String winningJenkinsUrl = jenkinsUrl;
                Collections.sort(jenkinsUrls, new Comparator<String>() {
                    @Override
                    public int compare(String o1, String o2) {
                        if (winningJenkinsUrl.equals(o1)) {
                            return -1;
                        }
                        if (winningJenkinsUrl.equals(o2)) {
                            return 1;
                        }
                        return 0;
                    }
                });
                if (tunnel != null) {
                    String[] tokens = tunnel.split(":", 3);
                    if (tokens.length != 2)
                        throw new IOException("Illegal tunneling parameter: " + tunnel);
                    if (tokens[0].length() > 0) host = tokens[0];
                    if (tokens[1].length() > 0) port = Integer.parseInt(tokens[1]);
                }

                //TODO: all the checks above do not make much sense if tunneling is enabled (JENKINS-52246)
                
                return new JnlpAgentEndpoint(host, port, identity, agentProtocolNames, selectedJenkinsURL);
            } finally {
                con.disconnect();
            }
        }
        if (firstError != null) {
            throw firstError;
        }
        return null;
    }

    private JnlpAgentEndpoint getJnlpAgentEndpointFromConfig() throws IOException {
        if (jenkinsUrls.size() > 1) {
            throw new IOException("Only one URL or Tunnel must be specified when HTTP Endpoint Check is disabled");
        }

        final String host;
        final int port;
        if (tunnel != null) {
            String[] tokens = tunnel.split(":", 3);
            if (tokens.length != 2)
                throw new IOException("Illegal tunneling parameter: " + tunnel);
            host = tokens[0];
            port = Integer.parseInt(tokens[1]);
        } else if (!jenkinsUrls.isEmpty()) {
            // URL like tcp://myjenkins:50000
            URL url = new URL(jenkinsUrls.get(0));
            host = url.getHost();
            port = url.getPort();
        } else {
            throw new IOException("Cannot locate URL, tunnel or URL are not set");
        }
        RSAPublicKey identity = null;
        try {
            identity = getIdentity(instanceIdentity);
            if(identity == null) throw new IOException("X-Instance-Identity parameter seems to be invalid");
        }
        catch (InvalidKeySpecException e) {
            throw new IOException("X-Instance-Identity parameter seems to be invalid");
        }

        //TODO: enforce protocols in such configuration (via CLI arg?)
        return new JnlpAgentEndpoint(host, port, identity, null, null);
    }
    
    private RSAPublicKey getIdentity(String base64EncodedIdentity) throws InvalidKeySpecException {
        if (base64EncodedIdentity == null) return null;
        try {
            byte[] encodedKey = Base64.decode(base64EncodedIdentity);
            if (encodedKey == null) return null;
            X509EncodedKeySpec spec = new X509EncodedKeySpec(encodedKey);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) kf.generatePublic(spec);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "The Java Language Specification mandates RSA as a supported algorithm", e);
        }
    }

    private boolean isPortVisible(String hostname, int port, int timeout) {
        boolean exitStatus = false;
        Socket s = null;

        try {
            s = new Socket();
            s.setReuseAddress(true);
            SocketAddress sa = new InetSocketAddress(hostname, port);
            s.connect(sa, timeout);
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
        }
        return exitStatus;
    }

    @Nonnull
    private URL toAgentListenerURL(@Nonnull String jenkinsUrl) throws MalformedURLException {
        return jenkinsUrl.endsWith("/")
                ? new URL(jenkinsUrl + "tcpSlaveAgentListener/")
                : new URL(jenkinsUrl + "/tcpSlaveAgentListener/");
    }

    public void waitForReady() throws InterruptedException {
        if (disableHttpEndpointCheck) {
            LOGGER.log(Level.INFO, "HTTP Endpoint Check is disabled, assuming that the target master is ready");
            return;
        }

        Thread t = Thread.currentThread();
        String oldName = t.getName();
        try {
            int retries = 0;
            while (true) {
                Thread.sleep(1000 * 10);
                try {
                    // Jenkins top page might be read-protected. see http://www.nabble
                    // .com/more-lenient-retry-logic-in-Engine.waitForServerToBack-td24703172.html
                    final String firstUrl = first(jenkinsUrls);
                    if (firstUrl == null) {
                        // returning here will cause the whole loop to be broken and all the urls to be tried again
                        return;
                    }
                    URL url = toAgentListenerURL(firstUrl);

                    retries++;
                    t.setName(oldName + ": trying " + url + " for " + retries + " times");

                    HttpURLConnection con =
                            (HttpURLConnection) openURLConnection(url, credentials, proxyCredentials, sslSocketFactory, disableHttpsCertValidation);
                    con.setConnectTimeout(5000);
                    con.setReadTimeout(5000);
                    con.connect();
                    if (con.getResponseCode() == 200) {
                        return;
                    }
                    LOGGER.log(Level.INFO,
                            "Master isn''t ready to talk to us on {0}. Will try again: response code={1}",
                            new Object[]{url, con.getResponseCode()});
                } catch (SocketTimeoutException | ConnectException | NoRouteToHostException e) {
                    LOGGER.log(INFO, "Failed to connect to the master. Will try again: {0} {1}",
                            new String[] { e.getClass().getName(), e.getMessage() });
                } catch (IOException e) {
                    // report the failure
                    LOGGER.log(INFO, "Failed to connect to the master. Will try again", e);
                }
            }
        } finally {
            t.setName(oldName);
        }
    }

    @CheckForNull
    static InetSocketAddress getResolvedHttpProxyAddress(@Nonnull String host, int port) throws IOException {
        InetSocketAddress targetAddress = null;
        Iterator<Proxy>
                proxies =
                ProxySelector.getDefault().select(URI.create(String.format("http://%s:%d", host, port))).iterator();
        while (targetAddress == null && proxies.hasNext()) {
            Proxy proxy = proxies.next();
            if (proxy.type() == Proxy.Type.DIRECT) {
                // Proxy.NO_PROXY with a DIRECT type is returned in two cases:
                // - when no proxy (none) has been configured in the JVM (either with system properties or by the operating system)
                // - when the host URI is part of the exclusion list defined by system property -Dhttp.nonProxyHosts
                //
                // Unfortunately, the Proxy class does not provide a way to differentiate both cases to fallback to
                // environment variables only when no proxy has been configured. Therefore, we have to recheck if the URI
                // host is in the exclusion list.
                //
                // Warning:
                //      This code only supports Java 9+ implementation where nonProxyHosts entries are not interpreted as regex expressions anymore.
                //      Wildcard at the beginning or the end of an expression are the only remaining supported behaviours (e.g. *.jenkins.io or 127.*)
                //      https://bugs.java.com/view_bug.do?bug_id=8035158
                //      http://hg.openjdk.java.net/jdk9/jdk9/jdk/rev/50a749f2cade
                String nonProxyHosts = System.getProperty("http.nonProxyHosts");
                if(nonProxyHosts != null && nonProxyHosts.length() != 0) {
                    // Build a list of regexps matching all nonProxyHosts entries
                    StringJoiner sj = new StringJoiner("|");
                    nonProxyHosts = nonProxyHosts.toLowerCase(Locale.ENGLISH);
                    for(String entry : nonProxyHosts.split("\\|")) {
                        if(entry.isEmpty())
                            continue;
                        else if(entry.startsWith("*"))
                            sj.add(".*" + Pattern.quote(entry.substring(1)));
                        else if(entry.endsWith("*"))
                            sj.add(Pattern.quote(entry.substring(0, entry.length() - 1)) + ".*");
                        else
                            sj.add(Pattern.quote(entry));
                        // Detect when the pattern contains multiple wildcard, which used to work previous to Java 9 (e.g. 127.*.*.*)
                        if(entry.split("\\*").length > 2)
                            LOGGER.log(Level.WARNING, "Using more than one wildcard is not supported in nonProxyHosts entries: {0}", entry);
                    }
                    Pattern nonProxyRegexps = Pattern.compile(sj.toString());
                    if(nonProxyRegexps.matcher(host.toLowerCase(Locale.ENGLISH)).matches()) {
                        return null;
                    } else {
                        break;
                    }
                }
            }
            if (proxy.type() == Proxy.Type.HTTP) {
                final SocketAddress address = proxy.address();
                if (!(address instanceof InetSocketAddress)) {
                    LOGGER.log(Level.WARNING, "Unsupported proxy address type {0}", (address != null ? address.getClass() : "null"));
                    continue;
                }
                InetSocketAddress proxyAddress = (InetSocketAddress) address;
                if (proxyAddress.isUnresolved())
                    proxyAddress = new InetSocketAddress(proxyAddress.getHostName(), proxyAddress.getPort());
                targetAddress = proxyAddress;
            }
        }
        if (targetAddress == null) {
            String httpProxy = System.getenv("http_proxy");
            if (httpProxy != null && !inNoProxyEnvVar(host)) {
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
    static URLConnection openURLConnection(URL url, String credentials, String proxyCredentials,
                                           SSLSocketFactory sslSocketFactory, boolean disableHttpsCertValidation) throws IOException {
        String httpProxy = null;
        // If http.proxyHost property exists, openConnection() uses it.
        if (System.getProperty("http.proxyHost") == null) {
            httpProxy = System.getenv("http_proxy");
        }
        URLConnection con = null;
        if (httpProxy != null && "http".equals(url.getProtocol()) && !inNoProxyEnvVar(url.getHost())) {
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
        if (credentials != null) {
            String encoding = Base64.encode(credentials.getBytes("UTF-8"));
            con.setRequestProperty("Authorization", "Basic " + encoding);
        }
        if (proxyCredentials != null) {
            String encoding = Base64.encode(proxyCredentials.getBytes("UTF-8"));
            con.setRequestProperty("Proxy-Authorization", "Basic " + encoding);
        }

        if (con instanceof HttpsURLConnection) {
            final HttpsURLConnection httpsConnection = (HttpsURLConnection) con;
            if (disableHttpsCertValidation) {
                LOGGER.log(Level.WARNING, "HTTPs certificate check is disabled for the endpoint.");

                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[]{new NoCheckTrustManager()}, new SecureRandom());
                    sslSocketFactory = ctx.getSocketFactory();

                    httpsConnection.setHostnameVerifier(new NoCheckHostnameVerifier());
                    httpsConnection.setSSLSocketFactory(sslSocketFactory);
                } catch (KeyManagementException | NoSuchAlgorithmException ex) {
                    // We could just suppress it, but the exception will unlikely happen.
                    // So let's just propagate the error and fail the resolution
                    throw new IOException("Cannot initialize the insecure HTTPs mode", ex);
                }

            } else if (sslSocketFactory != null) {
                httpsConnection.setSSLSocketFactory(sslSocketFactory);
                //FIXME: Is it really required in this path? Seems like a bug
                httpsConnection.setHostnameVerifier(new NoCheckHostnameVerifier());
            }
        }
        return con;
    }

    static boolean inNoProxyEnvVar(String host) {
    	return Util.inNoProxyEnvVar(host);
    }

    @CheckForNull
    private static List<String> header(@Nonnull HttpURLConnection connection, String... headerNames) {
        Map<String, List<String>> headerFields = connection.getHeaderFields();
        for (String headerName : headerNames) {
            for (Map.Entry<String, List<String>> entry: headerFields.entrySet()) {
                final String headerField = entry.getKey();
                if (headerField != null && headerField.equalsIgnoreCase(headerName)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    @CheckForNull
    private static String first(@CheckForNull List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    @Nonnull
    private static String defaultString(@CheckForNull String value, @Nonnull String defaultValue) {
        return value == null ? defaultValue : value;
    }
}
