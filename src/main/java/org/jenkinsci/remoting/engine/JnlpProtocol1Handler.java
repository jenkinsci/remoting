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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jenkinsci.remoting.nio.NioChannelHub;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;

/**
 * Implementation of the JNLP-connect protocol.
 *
 * The slave sends the master the slave name it wants to register as and the
 * computed HMAC of the slave name. If accepted the master will reply with a
 * confirmation response.
 *
 * This was the first protocol supported by Jenkins. JNLP slaves will use this
 * as a last resort when connecting to old versions of Jenkins masters.
 *
 * @since 3.0
 * @deprecated Replaced by JNLP4
 * @see JnlpProtocol4Handler
 */
@Deprecated
public class JnlpProtocol1Handler extends LegacyJnlpProtocolHandler<LegacyJnlpConnectionState> {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(JnlpProtocol1Handler.class.getName());

    /**
     * Constructor.
     *
     * @param clientDatabase the client database to use or {@code null} if client connections will not be required.
     * @param threadPool     the {@link ExecutorService} to run tasks on.
     * @param hub            the {@link NioChannelHub} to use or {@code null} to use blocking I/O.
     * @param preferNio      {@code true} means that the protocol should attempt to use NIO if possible
     */
    public JnlpProtocol1Handler(@Nullable JnlpClientDatabase clientDatabase, @Nonnull ExecutorService threadPool,
                                @Nullable NioChannelHub hub, boolean preferNio) {
        super(clientDatabase, threadPool, hub, preferNio);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return "JNLP-connect";
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
    void sendHandshake(@Nonnull LegacyJnlpConnectionState state, @Nonnull Map<String, String> headers)
            throws IOException, ConnectionRefusalException {
        String secretKey = headers.get(JnlpConnectionState.SECRET_KEY);
        if (secretKey == null) {
            throw new ConnectionRefusalException("Client headers missing " + JnlpConnectionState.SECRET_KEY);
        }
        String clientName = headers.get(JnlpConnectionState.CLIENT_NAME_KEY);
        if (clientName == null) {
            throw new ConnectionRefusalException("Client headers missing " + JnlpConnectionState.CLIENT_NAME_KEY);
        }
        // Initiate the handshake.
        state.fireBeforeProperties();
        DataOutputStream outputStream = state.getDataOutputStream();
        outputStream.writeUTF(PROTOCOL_PREFIX + getName());
        outputStream.writeUTF(secretKey);
        outputStream.writeUTF(clientName);
        outputStream.flush();

        DataInputStream inputStream = state.getDataInputStream();
        // Check if the server accepted.
        String response = EngineUtil.readLine(inputStream);
        if (!response.equals(GREETING_SUCCESS)) {
            throw new ConnectionRefusalException("Server didn't accept the handshake: " + response);
        }
        // we don't get any headers from the server in JNLP-connect
        state.fireAfterProperties(new HashMap<String, String>());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void receiveHandshake(@Nonnull LegacyJnlpConnectionState state, @Nonnull Map<String, String> headers) throws IOException {
        state.fireBeforeProperties();
        final String secret = state.getDataInputStream().readUTF();
        final String clientName = state.getDataInputStream().readUTF();
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(JnlpConnectionState.CLIENT_NAME_KEY, clientName);
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
