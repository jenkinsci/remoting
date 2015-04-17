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
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

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
class JnlpProtocol1 extends JnlpProtocol {

    JnlpProtocol1(String slaveName, String slaveSecret, EngineListenerSplitter events) {
        super(slaveName, slaveSecret, events);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    boolean performHandshake(DataOutputStream outputStream,
            BufferedInputStream inputStream) throws IOException {
        // Initiate the handshake.
        outputStream.writeUTF(PROTOCOL_PREFIX + NAME);
        outputStream.writeUTF(slaveSecret);
        outputStream.writeUTF(slaveName);

        // Check if the server accepted.
        String response = EngineUtil.readLine(inputStream);
        if (!response.equals(GREETING_SUCCESS)) {
            events.status("Server didn't accept the handshake: " + response);
            return false;
        }

        return true;
    }

    @Override
    Channel buildChannel(Socket socket, ChannelBuilder channelBuilder) throws IOException {
        return channelBuilder.build(
                new BufferedInputStream(socket.getInputStream()),
                new BufferedOutputStream(socket.getOutputStream()));
    }

    static final String NAME = "JNLP-connect";
}
