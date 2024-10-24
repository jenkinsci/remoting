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
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import javax.net.ssl.SSLContext;
import org.jenkinsci.remoting.nio.NioChannelHub;
import org.jenkinsci.remoting.protocol.IOHub;

/**
 * Builds a set of {@link JnlpProtocolHandler} instances from the supplied preconditions.
 *
 * @since 3.0
 */
public class JnlpProtocolHandlerFactory {
    /**
     * Flag to indicate that we expect all clients to provide client authentication over TLS.
     */
    private boolean needClientAuth;
    /**
     * The {@link IOHub}.
     */
    @CheckForNull
    private IOHub ioHub;
    /**
     * The {@link SSLContext}.
     */
    @CheckForNull
    private SSLContext context;
    /**
     * The {@link JnlpClientDatabase} or {@code null} if protocols will be only used in client mode.
     */
    @CheckForNull
    private JnlpClientDatabase clientDatabase;
    /**
     * The thread pool to use.
     */
    @NonNull
    private final ExecutorService threadPool;

    /**
     * {@code true} means that the protocols should attempt to use NIO if possible.
     */
    private boolean preferNio = true;

    /**
     * Constructor.
     *
     * @param threadPool the thread pool to use.
     */
    public JnlpProtocolHandlerFactory(@NonNull ExecutorService threadPool) {
        this.threadPool = threadPool;
        needClientAuth = false;
    }

    /**
     * Add a {@link NioChannelHub}.
     *
     * @param nioChannelHub the {@link NioChannelHub}.
     * @return {@code this} for method chaining.
     */
    public JnlpProtocolHandlerFactory withNioChannelHub(@CheckForNull NioChannelHub nioChannelHub) {
        /**
         * The {@link NioChannelHub} to use or {@code null}
         */
        return this;
    }

    /**
     * Add a {@link IOHub}.
     *
     * @param ioHub the {@link IOHub}.
     * @return {@code this} for method chaining.
     */
    public JnlpProtocolHandlerFactory withIOHub(@CheckForNull IOHub ioHub) {
        this.ioHub = ioHub;
        return this;
    }

    /**
     * Add a {@link SSLContext}.
     *
     * @param context the {@link SSLContext}.
     * @return {@code this} for method chaining.
     */
    public JnlpProtocolHandlerFactory withSSLContext(@CheckForNull SSLContext context) {
        this.context = context;
        return this;
    }

    /**
     * Set the policy on client authentication certificates to use for
     * {@link JnlpProtocolHandler#handle(Socket, Map, List)} calls.
     *
     * @param needClientAuth {@code true} to reject connections over TLS where the client does not present a client
     *                                   certificate.
     * @return {@code this} for method chaining.
     */
    public JnlpProtocolHandlerFactory withSSLClientAuthRequired(boolean needClientAuth) {
        this.needClientAuth = needClientAuth;
        return this;
    }

    /**
     * Set the I/O blocking mode preferences
     *
     * @param preferNio {@code true} to prefer using Non-Blocking I/O techniques, {@code false} to prefer
     *                              thread-per-connection Blocking I/O.
     * @return {@code this} for method chaining.
     */
    public JnlpProtocolHandlerFactory withPreferNonBlockingIO(boolean preferNio) {
        this.preferNio = preferNio;
        return this;
    }

    /**
     * Add a {@link JnlpClientDatabase}.
     *
     * @param clientDatabase the {@link JnlpClientDatabase}.
     * @return {@code this} for method chaining.
     */
    public JnlpProtocolHandlerFactory withClientDatabase(@CheckForNull JnlpClientDatabase clientDatabase) {
        this.clientDatabase = clientDatabase;
        return this;
    }

    /**
     * Creates the list of {@link JnlpProtocolHandler} instances that are available with the current configuration.
     *
     * @return the {@link JnlpProtocolHandler} instances, may be empty, never {@code null}
     */
    @NonNull
    public List<JnlpProtocolHandler<? extends JnlpConnectionState>> handlers() {
        List<JnlpProtocolHandler<? extends JnlpConnectionState>> result = new ArrayList<>();
        if (ioHub != null && context != null) {
            JnlpProtocol4Handler jnlpProtocol4Handler =
                    new JnlpProtocol4Handler(clientDatabase, threadPool, ioHub, context, needClientAuth, preferNio);
            result.add(new JnlpProtocol4ProxyHandler(jnlpProtocol4Handler));
            result.add(jnlpProtocol4Handler);
        }
        return result;
    }
}
