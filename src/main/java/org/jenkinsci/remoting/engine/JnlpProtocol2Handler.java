/*
 * The MIT License
 * 
 * Copyright (c) 2004-2016, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jenkinsci.remoting.nio.NioChannelHub;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;

/**
 * Implementation of the JNLP2-connect protocol.
 *
 * This is an extension of the JNLP1-connect protocol. On successful
 * connection to the master the slave will receive a cookie from the master,
 * which the slave stores.
 *
 * If the slave needs to reconnect it will send the same cookie as part of
 * the new connection request. The master can use the cookie to determine if
 * the incoming request is an initial connection request or a reconnection
 * and take appropriate action.
 * 
 * @since 3.0
 * @deprecated Replaced by JNLP4
 * @see JnlpProtocol4Handler
 */
@Deprecated
public class JnlpProtocol2Handler extends LegacyJnlpProtocolHandler<LegacyJnlpConnectionState> {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(JnlpProtocol2Handler.class.getName());

    /**
     * Constructor.
     *
     * @param clientDatabase the client database to use or {@code null} if client connections will not be required.
     * @param threadPool     the {@link ExecutorService} to run tasks on.
     * @param hub            the {@link NioChannelHub} to use or {@code null} to use blocking I/O.
     * @param preferNio      {@code true} means that the protocol should attempt to use NIO if possible.
     */
    public JnlpProtocol2Handler(@Nullable JnlpClientDatabase clientDatabase, @Nonnull ExecutorService threadPool,
                                @Nullable NioChannelHub hub, boolean preferNio) {
        super(clientDatabase, threadPool, hub, preferNio);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return "JNLP2-connect";
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public LegacyJnlpConnectionState createConnectionState(@Nonnull Socket socket,
                                                           @Nonnull List<? extends JnlpConnectionStateListener> listeners)
            throws IOException {
        return new LegacyJnlpConnectionState(socket, listeners);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void sendHandshake(@Nonnull LegacyJnlpConnectionState state, @Nonnull Map<String, String> headers) throws IOException {
        String secretKey = headers.get(JnlpConnectionState.SECRET_KEY);
        if (secretKey == null) {
            throw new ConnectionRefusalException("Client headers missing " + JnlpConnectionState.SECRET_KEY);
        }
        String clientName = headers.get(JnlpConnectionState.CLIENT_NAME_KEY);
        if (clientName == null) {
            throw new ConnectionRefusalException("Client headers missing " + JnlpConnectionState.CLIENT_NAME_KEY);
        }
        String cookie = headers.get(JnlpConnectionState.COOKIE_KEY);
        Properties props = new Properties();
        props.put(JnlpConnectionState.SECRET_KEY, secretKey);
        props.put(JnlpConnectionState.CLIENT_NAME_KEY, clientName);

        // If there is a cookie send that as well.
        if (cookie != null) {
            props.put(JnlpConnectionState.COOKIE_KEY, cookie);
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        props.store(o, null);

        state.fireBeforeProperties();
        // Initiate the handshake.
        DataOutputStream outputStream = state.getDataOutputStream();
        outputStream.writeUTF(PROTOCOL_PREFIX + getName());
        outputStream.writeUTF(o.toString("UTF-8"));
        outputStream.flush();

        DataInputStream inputStream = state.getDataInputStream();
        // Check if the server accepted.
        String response = EngineUtil.readLine(inputStream);
        if (!response.equals(GREETING_SUCCESS)) {
            throw new ConnectionRefusalException("Server didn't accept the handshake: " + response);
        }
        Properties responses = EngineUtil.readResponseHeaders(inputStream);
        Map<String, String> properties = new HashMap<String, String>();
        properties.putAll((Map) responses);
        state.fireAfterProperties(properties);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void receiveHandshake(@Nonnull LegacyJnlpConnectionState state, @Nonnull Map<String, String> headers)
            throws IOException {
        state.fireBeforeProperties();
        Properties request = new Properties();
        request.load(new ByteArrayInputStream(state.getDataInputStream().readUTF().getBytes("UTF-8")));
        Map<String, String> properties = new HashMap<String, String>();
        properties.putAll((Map) request);
        String clientName = properties.get(JnlpConnectionState.CLIENT_NAME_KEY);
        String secret = properties.remove(JnlpConnectionState.SECRET_KEY);
        JnlpClientDatabase clientDatabase = getClientDatabase();
        if (clientDatabase == null || !clientDatabase.exists(clientName)) {
            throw new ConnectionRefusalException("Unknown client name: " + clientName);
        }
        String secretKey = clientDatabase.getSecretOf(clientName);
        if (secretKey == null) {
            throw new ConnectionRefusalException("Unknown client name: " + clientName);
        }
        if (!secretKey.equals(secret)) {
            LOGGER.log(Level.WARNING, "An attempt was made to connect as {0} from {1} with an incorrect secret",
                    new Object[]{clientName, state.getSocket().getRemoteSocketAddress()});
            throw new ConnectionRefusalException("Authorization failure");
        }
        state.fireAfterProperties(properties);
        PrintWriter out = state.getPrintWriter();
        out.println(GREETING_SUCCESS);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            out.println(e.getKey() + ": " + e.getValue());
        }
        out.println(); // empty line to conclude the response header
        out.flush();
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    Channel buildChannel(@Nonnull LegacyJnlpConnectionState state) throws IOException {
        return state.getChannelBuilder().build(state.getSocket());
    }

}
