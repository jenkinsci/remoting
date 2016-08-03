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

import hudson.remoting.SocketChannelStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;
import org.jenkinsci.remoting.util.Charsets;

/**
 * Base class for {@link LegacyJnlpProtocolHandler} based protocols.
 *
 * @since FIXME
 */
public class LegacyJnlpConnectionState extends JnlpConnectionState {
    /**
     * Wrapping Socket input stream.
     */
    private final BufferedOutputStream bufferedOutputStream;
    /**
     * Wrapping Socket input stream.
     */
    private final DataInputStream dataInputStream;
    private final OutputStream socketOutputStream;
    private final InputStream socketInputStream;
    private DataOutputStream dataOutputStream;

    /**
     * For writing handshaking response.
     *
     * This is a poor design choice that we just carry forward for compatibility.
     * For better protocol design, {@link DataOutputStream} is preferred for newer
     * protocols.
     */
    private PrintWriter printWriter;

    public LegacyJnlpConnectionState(Socket socket, List<? extends JnlpConnectionStateListener> listeners) throws IOException {
        super(socket, listeners);
        socketOutputStream = SocketChannelStream.out(socket);
        this.bufferedOutputStream = new BufferedOutputStream(socketOutputStream);
        socketInputStream = SocketChannelStream.in(socket);
        this.dataInputStream = new DataInputStream(socketInputStream);
        this.printWriter = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8")), true);
    }

    public OutputStream getSocketOutputStream() {
        return socketOutputStream;
    }

    public InputStream getSocketInputStream() {
        return socketInputStream;
    }

    public DataInputStream getDataInputStream() {
        return dataInputStream;
    }

    @Deprecated
    public PrintWriter getPrintWriter() {
        if (printWriter == null) {
            printWriter = new PrintWriter(new OutputStreamWriter(bufferedOutputStream, Charsets.UTF_8), true);
        }
        return printWriter;
    }

    public DataOutputStream getDataOutputStream() {
        if (dataOutputStream == null) {
            dataOutputStream = new DataOutputStream(bufferedOutputStream);
        }
        return dataOutputStream;
    }

    public BufferedOutputStream getBufferedOutputStream() {
        return bufferedOutputStream;
    }
}
