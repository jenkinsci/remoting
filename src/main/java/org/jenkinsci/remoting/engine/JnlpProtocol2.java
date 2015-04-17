/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
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
import hudson.remoting.ChannelBuilder;
import hudson.remoting.EngineListenerSplitter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Properties;

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
 * @author Akshay Dayal
 */
class JnlpProtocol2 extends JnlpProtocol {

    /**
     * This cookie identifies the current connection, allowing us to force the
     * server to drop the client if we initiate a reconnection from our end
     * (even when the server still thinks the connection is alive.)
     */
    private String cookie;

    JnlpProtocol2(String slaveName, String slaveSecret, EngineListenerSplitter events) {
        super(slaveName, slaveSecret, events);
    }

    @Override
    public String getName() {
        return NAME;
    }

    String getCookie() {
        return cookie;
    }

    @Override
    boolean performHandshake(DataOutputStream outputStream,
            BufferedInputStream inputStream) throws IOException {
        initiateHandshake(outputStream);

        // Check if the server accepted.
        String response = EngineUtil.readLine(inputStream);
        if (!response.equals(GREETING_SUCCESS)) {
            events.status("Server didn't accept the handshake: " + response);
            return false;
        }

        Properties responses = EngineUtil.readResponseHeaders(inputStream);
        cookie = responses.getProperty(COOKIE_KEY);
        return true;
    }

    @Override
    Channel buildChannel(Socket socket, ChannelBuilder channelBuilder) throws IOException {
        return channelBuilder.build(
                new BufferedInputStream(socket.getInputStream()),
                new BufferedOutputStream(socket.getOutputStream()));
    }

    private void initiateHandshake(DataOutputStream outputStream) throws IOException {
        Properties props = new Properties();
        props.put(SECRET_KEY, slaveSecret);
        props.put(SLAVE_NAME_KEY, slaveName);

        // If there is a cookie send that as well.
        if (cookie != null)
            props.put(COOKIE_KEY, cookie);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        props.store(o, null);

        outputStream.writeUTF(PROTOCOL_PREFIX + NAME);
        outputStream.writeUTF(o.toString("UTF-8"));
    }

    static final String NAME = "JNLP2-connect";
    static final String SECRET_KEY = "Secret-Key";
    static final String SLAVE_NAME_KEY = "Node-Name";
    static final String COOKIE_KEY = "Cookie";
}
