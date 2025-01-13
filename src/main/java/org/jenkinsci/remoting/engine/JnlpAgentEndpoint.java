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
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.jenkinsci.remoting.util.KeyUtils;
import org.jenkinsci.remoting.util.ThrowableUtils;

/**
 * Represents a {@code TcpSlaveAgentListener} endpoint details.
 *
 * @since 3.0
 */
public class JnlpAgentEndpoint {
    /**
     * The hostname to connect to.
     */
    @NonNull
    private final String host;

    private final int port;
    /**
     * The {@code InstanceIdentity.getPublic()} of the instance or {@code null} if the instance identity was not
     * published.
     */
    @CheckForNull
    private final RSAPublicKey publicKey;
    /**
     * The {@code AgentProtocol.getName()}s supported by the instance or {@code null} if the supported protocols are
     * not published.
     */
    @CheckForNull
    private final Set<String> protocols;

    /**
     * Jenkins URL for the discovered endpoint.
     */
    @CheckForNull
    private final URL serviceUrl;

    @CheckForNull
    private final String proxyCredentials;

    /**
     * @deprecated Use {@link #JnlpAgentEndpoint(String, int, RSAPublicKey, Set, URL, String)}
     */
    @Deprecated
    public JnlpAgentEndpoint(
            @NonNull String host, int port, @CheckForNull RSAPublicKey publicKey, @CheckForNull Set<String> protocols) {
        this(host, port, publicKey, protocols, null, null);
    }

    /**
     * @deprecated Use {@link #JnlpAgentEndpoint(String, int, RSAPublicKey, Set, URL, String)}
     */
    @Deprecated
    public JnlpAgentEndpoint(
            @NonNull String host,
            int port,
            @CheckForNull RSAPublicKey publicKey,
            @CheckForNull Set<String> protocols,
            @CheckForNull URL serviceURL) {
        this(host, port, publicKey, protocols, serviceURL, null);
    }

    /**
     * Constructor for a remote {@code Jenkins} instance.
     *
     * @param host      the hostname.
     * @param port      the port.
     * @param publicKey the {@code InstanceIdentity.getPublic()} of the remote instance (if known).
     * @param protocols The supported protocols.
     * @param serviceURL URL of the service hosting the remoting endpoint.
     *                   Use {@code null} if it is not a web service or if the URL cannot be determined
     * @since 3.0
     */
    public JnlpAgentEndpoint(
            @NonNull String host,
            int port,
            @CheckForNull RSAPublicKey publicKey,
            @CheckForNull Set<String> protocols,
            @CheckForNull URL serviceURL,
            @CheckForNull String proxyCredentials) {
        if (port <= 0 || 65536 <= port) {
            throw new IllegalArgumentException("Port " + port + " is not in the range 1-65535");
        }
        this.host = host;
        this.port = port;
        this.publicKey = publicKey;
        this.protocols = protocols == null || protocols.isEmpty()
                ? null
                : Collections.unmodifiableSet(new LinkedHashSet<>(protocols));
        this.serviceUrl = serviceURL;
        this.proxyCredentials = proxyCredentials;
    }

    String describe() {
        return getHost() + ':' + getPort();
    }

    /**
     * Gets the socket address.
     *
     * @return the socket address
     */
    @NonNull
    public InetSocketAddress getAddress() {
        return new InetSocketAddress(host, port);
    }

    /**
     * Retrieves URL of the web service providing the remoting endpoint.
     * @return Service URL if available. {@code null} otherwise.
     */
    @CheckForNull
    public URL getServiceUrl() {
        return serviceUrl;
    }

    /**
     * Gets the hostname.
     *
     * @return the hostname.
     */
    @NonNull
    public String getHost() {
        return host;
    }

    /**
     * Gets the port.
     *
     * @return the port.
     */
    public int getPort() {
        return port;
    }

    /**
     * Gets the {@code InstanceIdentity.getPublic()} if available.
     *
     * @return the {@code InstanceIdentity.getPublic()} or {@code null}.
     */
    @CheckForNull
    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    /**
     * Gets the supported protocols if available.
     *
     * @return the supported protocols or {@code null}.
     */
    @CheckForNull
    public Set<String> getProtocols() {
        return protocols;
    }

    /**
     * Checks if the named protocol is supported.
     *
     * @param name the name of the protocol to check.
     * @return {@code false} if and only if the endpoint reports supported protocols and the named protocol is not in
     * the list of supported protocols.
     */
    public boolean isProtocolSupported(@NonNull String name) {
        return protocols == null || protocols.contains(name);
    }

    /**
     * Opens a socket connection to the remote endpoint.
     *
     * @param socketTimeout the {@link Socket#setSoTimeout(int)} to apply to the socket.
     * @return the socket.
     * @throws IOException if things go wrong.
     */
    @SuppressFBWarnings(
            value = "VA_FORMAT_STRING_USES_NEWLINE",
            justification = "Unsafe endline symbol is a pert of the protocol. Unsafe to fix it. See TODO " + "below")
    public Socket open(int socketTimeout) throws IOException {
        boolean isHttpProxy = false;
        InetSocketAddress targetAddress = null;
        SocketChannel channel = null;
        try {
            targetAddress = JnlpAgentEndpointResolver.getResolvedHttpProxyAddress(host, port);

            if (targetAddress == null) {
                targetAddress = new InetSocketAddress(host, port);
            } else {
                isHttpProxy = true;
            }

            // We open the socket using SocketChannel so that we are assured that the socket will always have
            // a socket channel. Sockets opened via Socket.open will typically not have a SocketChannel
            // and thus we will not have the ability to use NIO if we want to.
            channel = SocketChannel.open(targetAddress);
            Socket socket = channel.socket();

            socket.setTcpNoDelay(true); // we'll do buffering by ourselves

            // set read time out to avoid infinite hang. the time out should be long enough so as not
            // to interfere with normal operation. the main purpose of this is that when the other peer dies
            // abruptly, we shouldn't hang forever, and at some point we should notice that the connection
            // is gone.
            socket.setSoTimeout(socketTimeout);

            if (isHttpProxy) {
                String connectCommand = String.format("CONNECT %s:%s HTTP/1.1\r\nHost: %s\r\n", host, port, host);
                if (proxyCredentials != null) {
                    String encoding =
                            Base64.getEncoder().encodeToString(proxyCredentials.getBytes(StandardCharsets.UTF_8));
                    connectCommand += "Proxy-Authorization: Basic " + encoding + "\r\n";
                }
                connectCommand += "\r\n";
                socket.getOutputStream()
                        .write(connectCommand.getBytes(StandardCharsets.UTF_8)); // TODO: internationalized domain names

                BufferedInputStream is = new BufferedInputStream(socket.getInputStream());
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("Proxy socket closed");
                }
                String[] responseLineParts = line.trim().split(" ");
                if (responseLineParts.length < 2 || !responseLineParts[1].equals("200")) {
                    throw new IOException("Got a bad response from proxy: " + line);
                }
                while ((line = reader.readLine()) != null && !line.trim().isEmpty()) {
                    // Do nothing, scrolling through headers returned from proxy
                }
            }
            return socket;
        } catch (IOException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException suppressed) {
                    e = ThrowableUtils.chain(e, suppressed);
                }
            }
            String suffix = "";
            if (isHttpProxy) {
                suffix = " through proxy " + targetAddress;
            }
            throw new IOException("Failed to connect to " + host + ':' + port + suffix, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return host.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        JnlpAgentEndpoint that = (JnlpAgentEndpoint) o;

        if (port != that.port) {
            return false;
        }
        if (!KeyUtils.equals(publicKey, that.publicKey)) {
            return false;
        }
        if (!Objects.equals(protocols, that.protocols)) {
            return false;
        }
        if (host.equals(that.host)) {
            return true;
        }
        // now need to ensure that we do special handling for local addresses
        InetAddress thisAddr = this.getAddress().getAddress();
        InetAddress thatAddr = that.getAddress().getAddress();
        if (!thisAddr.getClass().equals(thatAddr.getClass())) {
            // differentiate Inet4Address from Inet6Address
            return false;
        }
        if (thisAddr.isAnyLocalAddress()) {
            return (thatAddr.isLinkLocalAddress() || thatAddr.isLoopbackAddress() || thatAddr.isAnyLocalAddress());
        }
        if (thatAddr.isAnyLocalAddress()) {
            return (thisAddr.isLinkLocalAddress() || thisAddr.isLoopbackAddress());
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "JnlpAgentEndpoint{" + "host=" + host + ", port="
                + port + ", publicKey="
                + KeyUtils.fingerprint(publicKey) + ", protocols="
                + protocols + '}';
    }
}
