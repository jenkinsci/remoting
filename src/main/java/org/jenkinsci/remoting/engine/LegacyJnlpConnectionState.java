package org.jenkinsci.remoting.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;

/**
 * @author Stephen Connolly
 */
public class LegacyJnlpConnectionState extends JnlpConnectionState {
    /**
     * Wrapping Socket input stream.
     */
    private final BufferedInputStream bufferedInputStream;
    private final BufferedOutputStream bufferedOutputStream;
    /**
     * Wrapping Socket input stream.
     */
    private DataInputStream dataInputStream;
    private DataOutputStream dataOutputStream;

    /**
     * For writing handshaking response.
     *
     * This is a poor design choice that we just carry forward for compatibility.
     * For better protocol design, {@link DataOutputStream} is preferred for newer
     * protocols.
     */
    private PrintWriter printWriter;

    public LegacyJnlpConnectionState(Socket socket, List<JnlpConnectionStateListener> listeners) throws IOException {
        super(socket, listeners);
        this.bufferedInputStream = new BufferedInputStream(socket.getInputStream());
        this.bufferedOutputStream = new BufferedOutputStream(socket.getOutputStream());
        this.dataInputStream = new DataInputStream(bufferedInputStream);
        this.printWriter = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8")), true);
    }

    public BufferedInputStream getBufferedInputStream() {
        return bufferedInputStream;
    }

    public DataInputStream getDataInputStream() {
        if (dataInputStream == null) {
            dataInputStream = new DataInputStream(bufferedInputStream);
        }
        return dataInputStream;
    }

    @Deprecated
    public PrintWriter getPrintWriter() {
        if (printWriter == null) {
            printWriter = new PrintWriter(new BufferedWriter(new OutputStreamWriter(bufferedOutputStream, Charset.forName("UTF-8"))), true);
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
