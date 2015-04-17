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

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * Handshake protocol used by JNLP slave when initiating a connection to
 * master. Protocols may be stateful.
 *
 * @author Akshay Dayal
 */
public abstract class JnlpProtocol {

    protected final String slaveName;
    protected final String slaveSecret;
    protected final EngineListenerSplitter events;

    JnlpProtocol(String slaveName, String slaveSecret, EngineListenerSplitter events) {
        this.slaveName = slaveName;
        this.slaveSecret = slaveSecret;
        this.events = events;
    }

    /**
     * Get the name of the protocol.
     */
    public abstract String getName();

    /**
     * Performs a handshake with the master and establishes a {@link Channel}.
     *
     * @param socket The established {@link Socket} connection to the master.
     * @param channelBuilder The {@link ChannelBuilder} to use.
     * @return The established channel if successful, else null.
     * @throws IOException
     */
    @Nullable
    public Channel establishChannel(Socket socket, ChannelBuilder channelBuilder) throws IOException {
        DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
        BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
        if(performHandshake(outputStream, inputStream)) {
            return buildChannel(socket, channelBuilder);
        }

        return null;
    }

    /**
     * Performs a handshake with the master.
     *
     * @param outputStream The stream to write into to initiate the handshake.
     * @param inputStream The stream to read responses from the master.
     * @return true iff handshake was successful.
     * @throws IOException
     */
    abstract boolean performHandshake(DataOutputStream outputStream,
            BufferedInputStream inputStream) throws IOException;

    /**
     * Builds a {@link Channel} which will be used for communication.
     *
     * @param socket The established {@link Socket} connection to the master.
     * @param channelBuilder The {@link ChannelBuilder} to use.
     * @return The constructed channel
     */
    abstract Channel buildChannel(Socket socket, ChannelBuilder channelBuilder) throws IOException;

    // The expected response from the master on successful completion of the
    // handshake.
    public static final String GREETING_SUCCESS = "Welcome";

    // Prefix when sending protocol name.
    static final String PROTOCOL_PREFIX = "Protocol:";
}
