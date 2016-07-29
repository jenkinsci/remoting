package org.jenkinsci.remoting.engine;

import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jenkinsci.remoting.nio.NioChannelHub;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;
import org.jenkinsci.remoting.util.ThrowableUtils;

/**
 * @author Stephen Connolly
 */
abstract class LegacyJnlpProtocolHandler<STATE extends LegacyJnlpConnectionState> extends JnlpProtocolHandler<STATE> {
    /**
     * The expected response from the master on successful completion of the
     * handshake.
     */
    public static final String GREETING_SUCCESS = "Welcome";

    /**
     * Prefix when sending protocol name.
     */
    /*package*/ static final String PROTOCOL_PREFIX = "Protocol:";
    @Nonnull
    private final ExecutorService threadPool;
    @Nullable // if we don't have a hub we will use blocking I/O
    private final NioChannelHub hub;

    public LegacyJnlpProtocolHandler(@Nonnull ExecutorService threadPool, @Nullable NioChannelHub hub) {
        this.threadPool = threadPool;
        this.hub = hub;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Channel> handle(final Socket socket, final Map<String, String> headers,
                                  List<JnlpConnectionStateListener> listeners)
            throws IOException {
        final STATE state = createConnectionState(socket, listeners);
        return threadPool.submit(new Callable<Channel>() {
            @Override
            public Channel call() throws Exception {
                try {
                    receiveHandshake(state, headers);
                    ChannelBuilder builder =
                            createChannelBuilder(String.format("Channel to %s", socket.getInetAddress()));
                    state.fireBeforeChannel(builder);
                    Channel channel = buildChannel(state);
                    state.fireAfterChannel(channel);
                    channel.addListener(new Channel.Listener() {
                        @Override
                        public void onClosed(Channel channel, IOException cause) {
                            state.fireChannelClosed(cause);
                            state.fireAfterDisconnect();
                            try {
                                socket.close();
                            } catch (IOException e) {
                                // ignore
                            }
                        }
                    });
                    return channel;
                } catch (IOException e) {
                    try {
                        socket.close();
                    } catch (IOException e1) {
                        ThrowableUtils.addSuppressed(e, e1);
                    }
                    state.fireAfterDisconnect();
                    throw e;
                }
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Channel> connect(final Socket socket, final Map<String, String> headers,
                                   List<JnlpConnectionStateListener> listeners) throws IOException {
        final STATE state = createConnectionState(socket, listeners);
        return threadPool.submit(new Callable<Channel>() {
            @Override
            public Channel call() throws Exception {
                try {
                    sendHandshake(state, headers);
                    ChannelBuilder builder =
                            createChannelBuilder(String.format("Channel to %s", socket.getInetAddress()));
                    state.fireBeforeChannel(builder);
                    Channel channel = buildChannel(state);
                    state.fireAfterChannel(channel);
                    channel.addListener(new Channel.Listener() {
                        @Override
                        public void onClosed(Channel channel, IOException cause) {
                            state.fireChannelClosed(cause);
                            state.fireAfterDisconnect();
                            try {
                                socket.close();
                            } catch (IOException e) {
                                // ignore
                            }
                        }
                    });
                    return channel;
                } catch (IOException e) {
                    try {
                        socket.close();
                    } catch (IOException e1) {
                        ThrowableUtils.addSuppressed(e, e1);
                    }
                    state.fireAfterDisconnect();
                    throw e;
                }
            }
        });
    }

    /**
     * Performs a handshake with the master. This method is responsible for calling
     * {@link JnlpConnectionState#fireBeforeProperties()} and {@link JnlpConnectionState#fireAfterProperties(Map)}.
     *
     * @param state   the state.
     * @param headers to send.
     * @return the headers received from the server.
     * @throws IOException                if things go wrong.
     * @throws ConnectionRefusalException if the connection was refused.
     */
    abstract void sendHandshake(STATE state, Map<String, String> headers) throws IOException;

    /**
     * Performs a handshake with the client. This method is responsible for calling
     * {@link JnlpConnectionState#fireBeforeProperties()} and {@link JnlpConnectionState#fireAfterProperties(Map)}.
     *
     * @param state the state.
     * @return the headers received from the client.
     * @throws IOException                if things go wrong.
     * @throws ConnectionRefusalException if the connection was refused.
     */
    abstract void receiveHandshake(STATE state, Map<String, String> headers) throws IOException;

    /**
     * Builds a {@link Channel} which will be used for communication.
     *
     * @param state the state
     * @return The constructed channel
     */
    abstract Channel buildChannel(STATE state) throws IOException;

    private ChannelBuilder createChannelBuilder(String nodeName) {
        if (hub == null) {
            return new ChannelBuilder(nodeName, threadPool);
        } else {
            return hub.newChannelBuilder(nodeName, threadPool);
        }
    }
}
