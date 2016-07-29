package org.jenkinsci.remoting.engine;

import hudson.remoting.Channel;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * Consolidates the protocol handling for both the server and the client ends of the connection.
 */
public abstract class JnlpProtocolHandler<STATE extends JnlpConnectionState> {
    /**
     * Get the name of the protocol.
     */
    public abstract String getName();

    /**
     * Whether this protocol is enabled for connecting.
     *
     * @return {@code true} if this protocol is enabled.
     * @since 2.59
     */
    public boolean isEnabled() {
        // map to the legacy names
        return !Boolean.getBoolean("org.jenkinsci.remoting.engine." + getClass().getSimpleName().replace("Handler", ".disabled"));
    }

    public abstract STATE createConnectionState(Socket socket, List<JnlpConnectionStateListener> listeners)
            throws IOException;

    /**
     * Handles an incoming client connection on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send.
     * @param listeners the listeners to approve and receive the connection.
     * @return a {@link Future} for the {@link Channel} to the server.
     * @throws IOException if the protocol cannot be initiated.
     */
    public final Future<Channel> handle(Socket socket, Map<String, String> headers,
                                        JnlpConnectionStateListener... listeners)
            throws IOException {
        return handle(socket, headers, Arrays.asList(listeners));
    }

    /**
     * Handles an incoming client connection on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send.
     * @param listeners the listeners to approve and receive the connection.
     * @return a {@link Future} for the {@link Channel} to the server.
     * @throws IOException if the protocol cannot be initiated.
     */
    public abstract Future<Channel> handle(Socket socket, Map<String, String> headers,
                                           List<JnlpConnectionStateListener> listeners)
            throws IOException;

    /**
     * Handles an outgoing connection to the server on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send.
     * @param listeners the listeners to approve and receive the connection.
     * @return a {@link Future} for the {@link Channel} to the server.
     * @throws IOException if the protocol cannot be initiated.
     */
    public final Future<Channel> connect(Socket socket, Map<String, String> headers,
                                         JnlpConnectionStateListener... listeners) throws IOException {
        return connect(socket, headers, Arrays.asList(listeners));
    }

    /**
     * Handles an outgoing connection to the server on the supplied socket.
     *
     * @param socket    the socket.
     * @param headers   the headers to send.
     * @param listeners the listeners to approve and receive the connection.
     * @return a {@link Future} for the {@link Channel} to the server.
     * @throws IOException if the protocol cannot be initiated.
     */
    public abstract Future<Channel> connect(Socket socket, Map<String, String> headers,
                                            List<JnlpConnectionStateListener> listeners) throws IOException;

}
