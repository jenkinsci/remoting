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

import hudson.remoting.Channel;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Consolidates the protocol handling for both the server and the client ends of the connection.
 */
public abstract class JnlpProtocolHandler<STATE extends JnlpConnectionState> {

    /**
     * The {@link JnlpClientDatabase} to use when authenticating incoming client connections, or {@code null} to refuse
     * incoming connections.
     */
    @CheckForNull
    private final JnlpClientDatabase clientDatabase;

    /**
     * Flag to indicate that the protocol should make best effort to use Non-Blocking I/O.
     */
    private final boolean preferNio;

    /**
     * Constructor.
     *
     * @param clientDatabase the {@link JnlpClientDatabase}.
     * @param preferNio      {@code true} means that the protocol should attempt to use NIO if possible.
     */
    /*package*/ JnlpProtocolHandler(@Nullable JnlpClientDatabase clientDatabase, boolean preferNio) {
        this.clientDatabase = clientDatabase;
        this.preferNio = preferNio;
    }

    /**
     * Gets the {@link JnlpClientDatabase}.
     *
     * @return the {@link JnlpClientDatabase} or {@code null}.
     */
    @CheckForNull
    public JnlpClientDatabase getClientDatabase() {
        return clientDatabase;
    }

    /**
     * Gets the preference for Non-Blocking I/O
     * @return {@code true} means that the protocol should attempt to use NIO if possible.
     */
    public boolean isPreferNio() {
        return preferNio;
    }

    /**
     * Get the name of the protocol.
     */
    public abstract String getName();

    /**
     * Whether this protocol is enabled for connecting.
     *
     * @return {@code true} if this protocol is enabled.
     */
    public boolean isEnabled() {
        // map to the legacy names
        return !Boolean.getBoolean(
                "org.jenkinsci.remoting.engine." + getClass().getSimpleName().replace("Handler", ".disabled"));
    }

    /**
     * Creates the {@link JnlpConnectionState} instance for this {@link JnlpProtocolHandler}.
     *
     * @param socket    the {@link Socket}
     * @param listeners the initial {@link JnlpConnectionStateListener} instances.
     * @return the {@link JnlpConnectionState} for this connection.
     * @throws IOException if something goes wrong.
     */
    @Nonnull
    protected abstract STATE createConnectionState(@Nonnull Socket socket,
                                                   @Nonnull List<? extends JnlpConnectionStateListener> listeners)
            throws IOException;

    /**
     * Handles an incoming client connection on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send.
     * @param listeners the listeners to approve and receive the connection.
     * @return a {@link Future} for the {@link Channel} to the server.
     * @throws IOException if the protocol cannot be initiated.
     */
    @Nonnull
    public final Future<Channel> handle(@Nonnull Socket socket, @Nonnull Map<String, String> headers,
                                        @Nonnull JnlpConnectionStateListener... listeners)
            throws IOException {
        return handle(socket, headers, Arrays.asList(listeners));
    }

    /**
     * Handles an incoming client connection on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send.
     * @param listeners the listeners to approve and receive the connection.
     * @return a {@link Future} for the {@link Channel} to the server.
     * @throws IOException if the protocol cannot be initiated.
     */
    @Nonnull
    public abstract Future<Channel> handle(@Nonnull Socket socket, @Nonnull Map<String, String> headers,
                                           @Nonnull List<? extends JnlpConnectionStateListener> listeners)
            throws IOException;

    /**
     * Handles an outgoing connection to the server on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send.
     * @param listeners the listeners to approve and receive the connection.
     * @return a {@link Future} for the {@link Channel} to the server.
     * @throws IOException if the protocol cannot be initiated.
     */
    @Nonnull
    public final Future<Channel> connect(@Nonnull Socket socket, @Nonnull Map<String, String> headers,
                                         @Nonnull JnlpConnectionStateListener... listeners) throws IOException {
        return connect(socket, headers, Arrays.asList(listeners));
    }

    /**
     * Handles an outgoing connection to the server on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send.
     * @param listeners the listeners to approve and receive the connection.
     * @return a {@link Future} for the {@link Channel} to the server.
     * @throws IOException if the protocol cannot be initiated.
     */
    @Nonnull
    public abstract Future<Channel> connect(@Nonnull Socket socket, @Nonnull Map<String, String> headers,
                                            @Nonnull List<? extends JnlpConnectionStateListener> listeners)
            throws IOException;

}
