package org.jenkinsci.remoting.engine;

/**
 * @author Kohsuke Kawaguchi
 */

import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import org.jenkinsci.remoting.nio.NioChannelHub;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Palette of objects to talk to the incoming JNLP slave connection.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.561
 */
public class JnlpSlaveHandshake {
    /**
     * Useful for creating a {@link Channel} with NIO as the underlying transport.
     */
    protected final NioChannelHub hub;

    /**
     * Socket connection to the slave.
     */
    protected final Socket socket;

    /**
     * Wrapping Socket input stream.
     */
    protected final DataInputStream in;

    /**
     * For writing handshaking response.
     *
     * This is a poor design choice that we just carry forward for compatibility.
     * For better protocol design, {@link DataOutputStream} is preferred for newer
     * protocols.
     */
    protected final PrintWriter out;

    /**
     * Bag of properties the JNLP agent have sent us during the hand-shake.
     */
    protected final Properties request = new Properties();

    private final ExecutorService threadPool;

    protected JnlpSlaveHandshake(NioChannelHub hub, ExecutorService threadPool, Socket socket, DataInputStream in, PrintWriter out) {
        this.hub = hub;
        this.threadPool = threadPool;
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    public NioChannelHub getHub() {
        return hub;
    }

    public Socket getSocket() {
        return socket;
    }

    public DataInputStream getIn() {
        return in;
    }

    public PrintWriter getOut() {
        return out;
    }

    public Properties getRequestProperties() {
        return request;
    }

    public String getRequestProperty(String name) {
        return request.getProperty(name);
    }


    /**
     * Sends the error output and bail out.
     */
    public void error(String msg) throws IOException {
        out.println(msg);
        LOGGER.log(Level.WARNING,Thread.currentThread().getName()+" is aborted: "+msg);
        socket.close();
    }

    /**
     * Tell the client that the server
     * is happy with the handshaking and is ready to move on to build a channel.
     */
    public void success(Properties response) {
        out.println(JnlpProtocol.GREETING_SUCCESS);
        for (Entry<Object, Object> e : response.entrySet()) {
            out.println(e.getKey()+": "+e.getValue());
        }
        out.println(); // empty line to conclude the response header
    }

    public ChannelBuilder createChannelBuilder(String nodeName) {
        if (hub==null)
            return new ChannelBuilder(nodeName, threadPool);
        else
            return hub.newChannelBuilder(nodeName, threadPool);
    }


    static final Logger LOGGER = Logger.getLogger(JnlpSlaveHandshake.class.getName());
}