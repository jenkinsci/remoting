/*
 * The MIT License
 * 
 * Copyright (c) 2004-2015, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
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
import hudson.remoting.Engine;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
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
 * @author Akshay Dayal
 */
@Deprecated
public class JnlpProtocol2Handler extends LegacyJnlpProtocolHandler<LegacyJnlpConnectionState> {

    static final String NAME = "JNLP2-connect";

    public JnlpProtocol2Handler(@Nonnull ExecutorService threadPool, @Nullable NioChannelHub hub) {
        super(threadPool, hub);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public LegacyJnlpConnectionState createConnectionState(Socket socket, List<JnlpConnectionStateListener> listeners)
            throws IOException {
        return new LegacyJnlpConnectionState(socket, listeners);
    }

    @Override
    void sendHandshake(LegacyJnlpConnectionState state, Map<String, String> headers) throws IOException {
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
        if (cookie != null)
            props.put(JnlpConnectionState.COOKIE_KEY, cookie);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        props.store(o, null);

        state.fireBeforeProperties();
        // Initiate the handshake.
        DataOutputStream outputStream = state.getDataOutputStream();
        outputStream.writeUTF(PROTOCOL_PREFIX + NAME);
        outputStream.writeUTF(o.toString("UTF-8"));
        outputStream.flush();

        BufferedInputStream inputStream = state.getBufferedInputStream();
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

    @Override
    void receiveHandshake(LegacyJnlpConnectionState state, Map<String, String> headers)
            throws IOException {
        state.fireBeforeProperties();
        Properties request = new Properties();
        request.load(new ByteArrayInputStream(state.getDataInputStream().readUTF().getBytes("UTF-8")));
        Map<String, String> properties = new HashMap<String, String>();
        properties.putAll((Map)request);
        state.fireAfterProperties(properties);
        PrintWriter out = state.getPrintWriter();
        out.println(GREETING_SUCCESS);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            out.println(e.getKey() + ": " + e.getValue());
        }
        out.println(); // empty line to conclude the response header
        out.flush();
    }

    @Override
    Channel buildChannel(LegacyJnlpConnectionState state) throws IOException {
        return state.getChannelBuilder().build(state.getBufferedInputStream(), state.getBufferedOutputStream());
    }

}
