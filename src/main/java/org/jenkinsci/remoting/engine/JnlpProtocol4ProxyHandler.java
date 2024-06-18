/*
 * The MIT License
 *
 * Copyright 2023 CloudBees, Inc.
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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Channel;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Passes {@link JnlpConnectionState#CLIENT_NAME_KEY} using an HTTP-style header and lets the server decide how to handle that.
 */
public final class JnlpProtocol4ProxyHandler extends JnlpProtocolHandler<JnlpConnectionState> {

    public static final String NAME = "JNLP4-connect-proxy";

    private static final Logger LOGGER = Logger.getLogger(JnlpProtocol4ProxyHandler.class.getName());

    private final JnlpProtocol4Handler delegate;

    public JnlpProtocol4ProxyHandler(JnlpProtocol4Handler delegate) {
        super(null, false); // unused, API design mistakes
        this.delegate = delegate;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    @NonNull
    public Future<Channel> connect(
            @NonNull Socket socket,
            @NonNull Map<String, String> headers,
            @NonNull List<? extends JnlpConnectionStateListener> listeners)
            throws IOException {
        LOGGER.fine("sending my name");
        DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
        dos.writeUTF("Protocol:" + NAME);
        LOGGER.fine(() -> "connecting with headers " + headers);
        String nodeName = headers.get(JnlpConnectionState.CLIENT_NAME_KEY);
        if (nodeName == null) {
            throw new IOException("expecting " + JnlpConnectionState.CLIENT_NAME_KEY);
        }
        PrintStream ps = new PrintStream(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        // HTTP-style headers
        ps.println(JnlpConnectionState.CLIENT_NAME_KEY + ": " + nodeName);
        ps.println();
        LOGGER.fine("sent headers, now delegating to regular protocol");
        return delegate.connect(socket, headers, listeners);
    }

    @Override
    @NonNull
    public Future<Channel> handle(
            @NonNull Socket socket,
            @NonNull Map<String, String> headers,
            @NonNull List<? extends JnlpConnectionStateListener> listeners)
            throws IOException {
        throw new UnsupportedOperationException("unused, API design mistake");
    }

    @Override
    @NonNull
    protected JnlpConnectionState createConnectionState(
            @NonNull Socket socket, @NonNull List<? extends JnlpConnectionStateListener> listeners) throws IOException {
        throw new UnsupportedOperationException("unused, API design mistake");
    }
}
