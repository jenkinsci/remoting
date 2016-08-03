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
    /**
     * The thread pool we can use for executing tasks.
     */
    @Nonnull
    private final ExecutorService threadPool;
    /**
     * The {@link NioChannelHub} to use for I/O, if {@code null} then we will have to use blocking I/O.
     */
    @Nullable // if we don't have a hub we will use blocking I/O
    private final NioChannelHub hub;

    /**
     * Constructor.
     *
     * @param clientDatabase the client database to use or {@code null} if client connections will not be required.
     * @param threadPool     the {@link ExecutorService} to run tasks on.
     * @param hub            the {@link NioChannelHub} to use or {@code null} to use blocking I/O.
     */
    public LegacyJnlpProtocolHandler(@Nullable JnlpClientDatabase clientDatabase, @Nonnull ExecutorService threadPool,
                                     @Nullable NioChannelHub hub) {
        super(clientDatabase);
        this.threadPool = threadPool;
        this.hub = hub;
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public Future<Channel> handle(@Nonnull final Socket socket, @Nonnull final Map<String, String> headers,
                                  @Nonnull List<? extends JnlpConnectionStateListener> listeners)
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
                } catch (IllegalStateException e) {
                    try {
                        socket.close();
                    } catch (IOException e1) {
                        ThrowableUtils.addSuppressed(e, e1);
                    }
                    state.fireAfterDisconnect();
                    throw new IOException(e);
                }
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public Future<Channel> connect(@Nonnull final Socket socket, @Nonnull final Map<String, String> headers,
                                   @Nonnull List<? extends JnlpConnectionStateListener> listeners) throws IOException {
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
                } catch (IllegalStateException e) {
                    try {
                        socket.close();
                    } catch (IOException e1) {
                        ThrowableUtils.addSuppressed(e, e1);
                    }
                    state.fireAfterDisconnect();
                    throw new IOException(e);
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
     * @throws IOException                if things go wrong.
     * @throws ConnectionRefusalException if the connection was refused.
     */
    abstract void sendHandshake(@Nonnull STATE state, @Nonnull Map<String, String> headers) throws IOException;

    /**
     * Performs a handshake with the client. This method is responsible for calling
     * {@link JnlpConnectionState#fireBeforeProperties()} and {@link JnlpConnectionState#fireAfterProperties(Map)}.
     *
     * @param state the state.
     * @throws IOException                if things go wrong.
     * @throws ConnectionRefusalException if the connection was refused.
     */
    abstract void receiveHandshake(@Nonnull STATE state, @Nonnull Map<String, String> headers) throws IOException;

    /**
     * Builds a {@link Channel} which will be used for communication.
     *
     * @param state the state
     * @return The constructed channel
     */
    @Nonnull
    abstract Channel buildChannel(@Nonnull STATE state) throws IOException;

    /**
     * Creates the {@link ChannelBuilder} to use.
     * @param clientName the client name to create the builder for.
     * @return the {@link ChannelBuilder}
     */
    @Nonnull
    private ChannelBuilder createChannelBuilder(String clientName) {
        if (hub == null) {
            return new ChannelBuilder(clientName, threadPool);
        } else {
            return hub.newChannelBuilder(clientName, threadPool);
        }
    }
}
