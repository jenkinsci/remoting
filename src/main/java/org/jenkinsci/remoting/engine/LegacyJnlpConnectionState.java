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
 * @author Stephen Connolly
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
