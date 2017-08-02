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
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

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

    /**
     * If specified, only the protocols from the list will be tried during the connection.
     * The option provides protocol names, but the order of the check is defined internally and cannot be changed.
     * This option can be also used in order to workaround issues when the headers cannot be delivered
     * from the server due to whatever reason (e.g. JENKINS-41730).
     * @since TODO
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

    public String getTunnel() {
        return tunnel;
    }

    public void setTunnel(String tunnel) {
        this.tunnel = tunnel;
    }

    @CheckForNull
    public JnlpAgentEndpoint resolve() throws IOException {
        IOException firstError = null;
        for (String jenkinsUrl : jenkinsUrls) {
            
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
                    (HttpURLConnection) openURLConnection(salURL, credentials, proxyCredentials, sslSocketFactory);
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
                RSAPublicKey identity = null;
                if (idHeader != null) {
                    try {
                        byte[] encodedKey = Base64.decode(idHeader);
                        if (encodedKey == null) {
                            firstError = chain(firstError, new IOException(
                                    salURL + " appears to be publishing an invalid X-Instance-Identity."));
                            continue;
                        }
                        X509EncodedKeySpec spec = new X509EncodedKeySpec(encodedKey);
                        KeyFactory kf = KeyFactory.getInstance("RSA");
                        identity = (RSAPublicKey) kf.generatePublic(spec);
                    } catch (InvalidKeySpecException e) {
                        firstError = chain(firstError, new IOException(
                                salURL + " appears to be publishing an invalid X-Instance-Identity."));
                        continue;
                    } catch (NoSuchAlgorithmException e) {
                        throw new IllegalStateException(
                                "The Java Language Specification mandates RSA as a supported algorithm", e);
                    }
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

    @CheckForNull
    private URL toAgentListenerURL(@CheckForNull String jenkinsUrl) throws MalformedURLException {
        return jenkinsUrl == null ? null : jenkinsUrl.endsWith("/")
                ? new URL(jenkinsUrl + "tcpSlaveAgentListener/")
                : new URL(jenkinsUrl + "/tcpSlaveAgentListener/");
    }

    public void waitForReady() throws InterruptedException {
        Thread t = Thread.currentThread();
        String oldName = t.getName();
        try {
            int retries = 0;
            while (true) {
                Thread.sleep(1000 * 10);
                try {
                    // Jenkins top page might be read-protected. see http://www.nabble
                    // .com/more-lenient-retry-logic-in-Engine.waitForServerToBack-td24703172.html
                    URL url = toAgentListenerURL(first(jenkinsUrls));
                    if (url == null) {
                        // returning here will cause the whole loop to be broken and all the urls to be tried again
                        return;
                    }

                    retries++;
                    t.setName(oldName + ": trying " + url + " for " + retries + " times");

                    HttpURLConnection con =
                            (HttpURLConnection) openURLConnection(url, credentials, proxyCredentials, sslSocketFactory);
                    con.setConnectTimeout(5000);
                    con.setReadTimeout(5000);
                    con.connect();
                    if (con.getResponseCode() == 200) {
                        return;
                    }
                    LOGGER.log(Level.INFO,
                            "Master isn't ready to talk to us on {0}. Will retry again: response code={1}",
                            new Object[]{url, con.getResponseCode()});
                } catch (IOException e) {
                    // report the failure
                    LOGGER.log(INFO, "Failed to connect to the master. Will retry again", e);
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
                break;
            }
            if (proxy.type() == Proxy.Type.HTTP) {
                final SocketAddress address = proxy.address();
                if (!(address instanceof InetSocketAddress)) {
                    System.err.println(
                            "Unsupported proxy address type " + (address != null ? address.getClass() : "null"));
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
                    System.err.println("Not use http_proxy environment variable which is invalid: " + e.getMessage());
                }
            }
        }
        return targetAddress;
    }

    /**
     * Gets URL connection.
     * If http_proxy environment variable exists,  the connection uses the proxy.
     * Credentials can be passed e.g. to support running Jenkins behind a (reverse) proxy requiring authorization
     */
    static URLConnection openURLConnection(URL url, String credentials, String proxyCredentials,
                                           SSLSocketFactory sslSocketFactory) throws IOException {
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
                System.err.println(
                        "Not use http_proxy property or environment variable which is invalid: " + e.getMessage());
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
        if (con instanceof HttpsURLConnection && sslSocketFactory != null) {
            ((HttpsURLConnection) con).setSSLSocketFactory(sslSocketFactory);
        }
        return con;
    }

    /**
     * Check if given URL is in the exclusion list defined by the no_proxy environment variable.
     * On most *NIX system wildcards are not supported but if one top domain is added, all related subdomains will also
     * be ignored. Both "mit.edu" and ".mit.edu" are valid syntax.
     * http://www.gnu.org/software/wget/manual/html_node/Proxies.html
     *
     * Regexp:
     * - \Q and \E: https://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html
     * - To match IPV4/IPV/FQDN: Regular Expressions Cookbook, 2nd Edition (ISBN: 9781449327453)
     *
     * Warning: this method won't match shortened representation of IPV6 address
     */
    static boolean inNoProxyEnvVar(String host) {
        String noProxy = System.getenv("no_proxy");
        if (noProxy != null) {
            noProxy = noProxy.trim()
                    // Remove spaces
                    .replaceAll("\\s+", "")
                    // Convert .foobar.com to foobar.com
                    .replaceAll("((?<=^|,)\\.)*(([a-z0-9]+(-[a-z0-9]+)*\\.)+[a-z]{2,})(?=($|,))", "$2");

            if (!noProxy.isEmpty()) {
                // IPV4 and IPV6
                if (host.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$") || host
                        .matches("^(?:[a-fA-F0-9]{1,4}:){7}[a-fA-F0-9]{1,4}$")) {
                    return noProxy.matches(".*(^|,)\\Q" + host + "\\E($|,).*");
                } else {
                    int depth = 0;
                    // Loop while we have a valid domain name: acme.com
                    // We add a safeguard to avoid a case where the host would always be valid because the regex would
                    // for example fail to remove subdomains.
                    // According to Wikipedia (no RFC defines it), 128 is the max number of subdivision for a valid
                    // FQDN:
                    // https://en.wikipedia.org/wiki/Subdomain#Overview
                    while (host.matches("^([a-z0-9]+(-[a-z0-9]+)*\\.)+[a-z]{2,}$") && depth < 128) {
                        ++depth;
                        // Check if the no_proxy contains the host
                        if (noProxy.matches(".*(^|,)\\Q" + host + "\\E($|,).*")) {
                            return true;
                        }
                        // Remove first subdomain: master.jenkins.acme.com -> jenkins.acme.com
                        else {
                            host = host.replaceFirst("^[a-z0-9]+(-[a-z0-9]+)*\\.", "");
                        }
                    }
                }
            }
        }

        return false;
    }

    @CheckForNull
    private static List<String> header(@Nonnull HttpURLConnection connection, String... headerNames) {
        Map<String, List<String>> headerFields = connection.getHeaderFields();
        for (String headerName : headerNames) {
            for (String headerField : headerFields.keySet()) {
                if (headerField != null && headerField.equalsIgnoreCase(headerName)) {
                    return headerFields.get(headerField);
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
