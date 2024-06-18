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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jcip.annotations.NotThreadSafe;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;

/**
 * Represents the state of a connection event. This object should not be retained by the
 * {@link JnlpConnectionStateListener}
 *
 * @since 3.0
 */
@NotThreadSafe
public class JnlpConnectionState {

    /**
     * The current iterator being used to process the listener notifications.
     */
    private static final ThreadLocal<Iterator<JnlpConnectionStateListener>> fireIterator = new ThreadLocal<>();

    /**
     * The property name for the secret key.
     */
    public static final String SECRET_KEY = "Secret-Key";
    /**
     * The property name for the client name key.
     */
    public static final String CLIENT_NAME_KEY = "Node-Name";
    /**
     * The property name for the cookie name key.
     */
    public static final String COOKIE_KEY = "JnlpAgentProtocol.cookie";

    /**
     * Socket connection to the agent.
     */
    @CheckForNull
    private final Socket socket;

    @NonNull
    private String remoteEndpointDescription = "<unknown>";

    /**
     * The {@link JnlpConnectionStateListener} instances that are still interested in this connection event.
     */
    private final List<JnlpConnectionStateListener> listeners;
    /**
     * The connection properties, this is {@code null} in
     * {@link JnlpConnectionStateListener#beforeProperties(JnlpConnectionState)} and non-null thereafter.
     */
    @CheckForNull
    private Map<String, String> properties;
    /**
     * The {@link ChannelBuilder, this is {@code null} except in
     * {@link JnlpConnectionStateListener#beforeChannel(JnlpConnectionState)}.
     */
    @CheckForNull
    private ChannelBuilder channelBuilder;
    /**
     * The {@link Channel}, this is {@code null} until
     * {@link JnlpConnectionStateListener#afterChannel(JnlpConnectionState)}.
     */
    @CheckForNull
    private Channel channel;
    /**
     * Holds the reason for connection rejection
     */
    @CheckForNull
    private ConnectionRefusalException rejection;
    /**
     * The reason for the channel being closed (if supplied);
     */
    @CheckForNull
    private IOException closeCause;
    /**
     * The current state in the event lifecycle.
     */
    private State lifecycle = State.INITIALIZED;
    /**
     * Any connection specific state that the listener that has {@link #approve()} for the connection wants to
     * track between callbacks.
     */
    @CheckForNull
    private ListenerState stash;

    /**
     * Constructor.
     *
     * @param socket    the {@link Socket}.
     * @param listeners the {@link JnlpConnectionStateListener} instances.
     */
    public JnlpConnectionState(@Nullable Socket socket, List<? extends JnlpConnectionStateListener> listeners) {
        this.socket = socket;
        this.listeners = new ArrayList<>(listeners);
    }

    /**
     * Gets the socket that the connection is on.
     * <p>This should be considered deprecated except in situations where you know an actual socket is involved.
     * Use {@link #getRemoteEndpointDescription} for logging purposes.
     * @return an actual socket, or just a stub
     */
    @SuppressFBWarnings(value = "UNENCRYPTED_SOCKET", justification = "just a stub")
    @NonNull
    public Socket getSocket() {
        return socket != null ? socket : new Socket();
    }

    /**
     * Description of the remote endpoint to which {@link #getSocket} is connected, if using an actual socket and it is actually connected.
     * Or may be some other text identifying a remote client.
     * @return some text suitable for debug logs
     */
    @NonNull
    public String getRemoteEndpointDescription() {
        return socket != null ? String.valueOf(socket.getRemoteSocketAddress()) : remoteEndpointDescription;
    }

    /**
     * Set a specific value for {@link #getRemoteEndpointDescription}.
     */
    public void setRemoteEndpointDescription(@NonNull String description) {
        remoteEndpointDescription = description;
    }

    /**
     * Gets the connection properties.
     *
     * @return the connection properties.
     * @throws IllegalStateException if invoked before
     *                               {@link JnlpConnectionStateListener#afterProperties(JnlpConnectionState)}
     */
    public Map<String, String> getProperties() {
        if (lifecycle.compareTo(State.AFTER_PROPERTIES) < 0) {
            throw new IllegalStateException("The connection properties have not been exchanged yet");
        }
        return properties;
    }

    /**
     * Gets the named connection property.
     *
     * @param name the property name.
     * @return the connection property.
     * @throws IllegalStateException if invoked before
     *                               {@link JnlpConnectionStateListener#afterProperties(JnlpConnectionState)}
     */
    public String getProperty(String name) {
        if (lifecycle.compareTo(State.AFTER_PROPERTIES) < 0) {
            throw new IllegalStateException("The connection properties have not been exchanged yet");
        }
        return properties == null ? null : properties.get(name);
    }

    /**
     * Gets the {@link ChannelBuilder} that will be used to create the connection's {@link Channel}.
     *
     * @return the {@link ChannelBuilder}
     * @throws IllegalStateException if invoked outside of
     *                               {@link JnlpConnectionStateListener#beforeChannel(JnlpConnectionState)}
     */
    public ChannelBuilder getChannelBuilder() {
        if (lifecycle.compareTo(State.APPROVED) < 0) {
            throw new IllegalStateException("The connection has not been approved yet");
        }
        if (lifecycle.compareTo(State.AFTER_CHANNEL) >= 0) {
            throw new IllegalStateException("The channel has already been built");
        }
        return channelBuilder;
    }

    /**
     * Gets the connection's {@link Channel}.
     *
     * @return the {@link Channel} (may be closed already), may be {@code null} in
     * {@link JnlpConnectionStateListener#afterDisconnect(JnlpConnectionState)} if the socket was closed by
     * the client.
     * @throws IllegalStateException if invoked before
     *                               {@link JnlpConnectionStateListener#afterChannel(JnlpConnectionState)}
     */
    public Channel getChannel() {
        if (lifecycle.compareTo(State.AFTER_CHANNEL) < 0) {
            throw new IllegalStateException("The channel has not been built yet");
        }
        return channel;
    }

    /**
     * Gets the reason for the channel being closed if available.
     *
     * @return the reason or {@code null} if termination was normal.
     * @throws IllegalStateException if invoked before
     *                               {@link JnlpConnectionStateListener#channelClosed(JnlpConnectionState)}
     */
    @CheckForNull
    public IOException getCloseCause() {
        if (lifecycle.compareTo(State.CHANNEL_CLOSED) < 0) {
            throw new IllegalStateException("The channel has not been closed yet");
        }
        return closeCause;
    }

    /**
     * Signals that the current {@link JnlpConnectionStateListener} is not interested in this event any more. If all
     * {@link JnlpConnectionStateListener} implementations ignore the event then the connection will be
     * rejected.
     *
     * @throws IllegalStateException if invoked outside of
     *                               {@link JnlpConnectionStateListener#beforeProperties(JnlpConnectionState)} or
     *                               {@link JnlpConnectionStateListener#afterProperties(JnlpConnectionState)}.
     */
    public void ignore() {
        if (lifecycle.compareTo(State.AFTER_PROPERTIES) > 0) {
            throw new IllegalStateException("Events cannot be ignored after approval/rejection");
        }
        Iterator<JnlpConnectionStateListener> iterator = JnlpConnectionState.fireIterator.get();
        if (iterator == null) {
            throw new IllegalStateException(
                    "Events can only be ignored from within the JnlpConnectionStateListener notification methods");
        }
        iterator.remove();
    }

    /**
     * Signals that the current {@link JnlpConnectionStateListener} is declaring ownership of this event, approving
     * the connection and all other {@link JnlpConnectionStateListener} instances will now be ignored.
     * This method must be called by at least one {@link JnlpConnectionStateListener} or the connection will be
     * rejected.
     *
     * @throws IllegalStateException if invoked outside of
     *                               {@link JnlpConnectionStateListener#beforeProperties(JnlpConnectionState)} or
     *                               {@link JnlpConnectionStateListener#afterProperties(JnlpConnectionState)}.
     */
    public void approve() {
        if (lifecycle.compareTo(State.AFTER_PROPERTIES) > 0) {
            throw new IllegalStateException("Events cannot be approved after approval/rejection");
        }
        lifecycle = State.APPROVED;
    }

    /**
     * Signals that the current {@link JnlpConnectionStateListener} is declaring ownership of this event, rejecting
     * the connection and all other {@link JnlpConnectionStateListener} instances will now be ignored.
     *
     * @throws IllegalStateException if invoked outside of
     *                               {@link JnlpConnectionStateListener#beforeProperties(JnlpConnectionState)} or
     *                               {@link JnlpConnectionStateListener#afterProperties(JnlpConnectionState)}.
     */
    public void reject(ConnectionRefusalException reason) {
        if (lifecycle.compareTo(State.AFTER_PROPERTIES) > 0) {
            throw new IllegalStateException("Events cannot be rejected after approval/rejection");
        }
        lifecycle = State.REJECTED;
        rejection = reason;
    }

    /**
     * Retrieves the previously stashed state.
     * @param clazz the expected class of the stashed state.
     * @param <S> the expected class of the stashed state.
     * @return the stashed state.
     * @throws IllegalStateException if invoked before {@link #approve()}
     * @see #setStash(ListenerState)
     */
    @CheckForNull
    public <S extends ListenerState> S getStash(Class<S> clazz) {
        if (lifecycle.compareTo(State.APPROVED) < 0) {
            throw new IllegalStateException("The connection has not been approved yet");
        }
        return clazz.cast(stash);
    }

    /**
     * Stores some listener specific state for later retrieval.
     *
     * @param stash the state to stash.
     * @param <S>   the expected class of the stashed state.
     * @throws IllegalStateException if invoked before {@link #approve()}
     * @see #getStash(Class)
     */
    public <S extends ListenerState> void setStash(@CheckForNull S stash) {
        if (lifecycle.compareTo(State.APPROVED) < 0) {
            throw new IllegalStateException("The connection has not been approved yet");
        }
        this.stash = stash;
    }

    /**
     * Encapsulates the common event dispatch logic.
     *
     * @param handler the logic to apply.
     */
    private void fire(EventHandler handler) {
        Iterator<JnlpConnectionStateListener> iterator = listeners.iterator();
        JnlpConnectionState.fireIterator.set(iterator);
        try {
            final State lifecycle = this.lifecycle;
            while (iterator.hasNext()) {
                JnlpConnectionStateListener current = iterator.next();
                handler.invoke(current, this);
                if (lifecycle != this.lifecycle) {
                    // a listener has changed the state, thus they are the owner
                    listeners.retainAll(Set.of(current));
                    return;
                }
            }
        } finally {
            JnlpConnectionState.fireIterator.remove();
        }
    }

    /**
     * Advances the connection state to indicate that a connection has been "secured" and the property exchange
     * is about to take place.
     *
     * @throws ConnectionRefusalException if the connection has been refused.
     */
    public void fireBeforeProperties() throws ConnectionRefusalException {
        if (lifecycle != State.INITIALIZED) {
            throw new IllegalStateException("fireBeforeProperties cannot be invoked at lifecycle " + lifecycle);
        }
        lifecycle = State.BEFORE_PROPERTIES;
        // TODO fire(JnlpConnectionStateListener::beforeProperties); // Java 8
        fire(JnlpConnectionStateListener::beforeProperties);
        // check for early rejection
        if (lifecycle == State.REJECTED || listeners.isEmpty()) {
            lifecycle = State.REJECTED;
            ConnectionRefusalException rejection = this.rejection;
            this.rejection = null;
            if (rejection != null) {
                throw rejection;
            }
            throw new ConnectionRefusalException("No listeners interested in connection");
        }
    }

    /**
     * Advances the connection state to indicate that the property exchange has completed.
     *
     * @throws ConnectionRefusalException if the connection has been refused.
     */
    public void fireAfterProperties(@NonNull Map<String, String> properties) throws ConnectionRefusalException {
        if (lifecycle != State.BEFORE_PROPERTIES) {
            throw new IllegalStateException("fireAfterProperties cannot be invoked at lifecycle " + lifecycle);
        }
        this.properties = new HashMap<>(properties);
        lifecycle = State.AFTER_PROPERTIES;
        // TODO fire(JnlpConnectionStateListener::afterProperties);
        fire(JnlpConnectionStateListener::afterProperties);
        // must have approval or else connection is rejected
        if (lifecycle != State.APPROVED || listeners.isEmpty()) {
            lifecycle = State.REJECTED;
            ConnectionRefusalException rejection = this.rejection;
            this.rejection = null;
            if (rejection != null) {
                throw rejection;
            }
            throw new ConnectionRefusalException("No listeners interested in connection");
        }
    }

    /**
     * Advances the connection state to indicate that the channel is about to be created.
     *
     * @param builder the {@link ChannelBuilder} that will be used to create the channel.
     */
    public void fireBeforeChannel(ChannelBuilder builder) {
        if (lifecycle != State.APPROVED) {
            throw new IllegalStateException("fireBeforeChannel cannot be invoked at lifecycle " + lifecycle);
        }
        lifecycle = State.BEFORE_CHANNEL;
        this.channelBuilder = builder;
        // TODO fire(JnlpConnectionStateListener::beforeChannel);
        fire(JnlpConnectionStateListener::beforeChannel);
    }

    /**
     * Advances the connection state to indicate that the channel has been created.
     *
     * @param channel the {@link Channel} (may be closed already but should not unless there is a serious race with
     *                the remote).
     */
    public void fireAfterChannel(Channel channel) {
        if (lifecycle != State.BEFORE_CHANNEL) {
            throw new IllegalStateException("fireAfterChannel cannot be invoked at lifecycle " + lifecycle);
        }
        lifecycle = State.AFTER_CHANNEL;
        this.channelBuilder = null;
        this.channel = channel;
        // TODO fire(JnlpConnectionStateListener::afterChannel);
        fire(JnlpConnectionStateListener::afterChannel);
    }

    /**
     * Advances the connection state to indicate that the channel has been closed.
     *
     * @param cause the reason why the channel was closed or {@code null} if normally closed
     */
    public void fireChannelClosed(@CheckForNull IOException cause) {
        if (lifecycle.compareTo(State.BEFORE_CHANNEL) < 0) {
            throw new IllegalStateException("fireChannelClosed cannot be invoked at lifecycle " + lifecycle);
        }
        closeCause = cause;
        lifecycle = State.CHANNEL_CLOSED;
        // TODO fire(JnlpConnectionStateListener::channelClosed);
        fire(JnlpConnectionStateListener::channelClosed);
    }

    /**
     * Advances the connection state to indicate that the socket has been closed.
     */
    public void fireAfterDisconnect() {
        if (lifecycle == State.AFTER_CHANNEL) {
            fireChannelClosed(null);
        }
        lifecycle = State.DISCONNECTED;
        // TODO fire(JnlpConnectionStateListener::afterDisconnect);
        fire(JnlpConnectionStateListener::afterDisconnect);
    }

    /**
     * Lambda interface used by {@link JnlpConnectionState#fire(EventHandler)}
     */
    private interface EventHandler {
        /**
         * Invokes the event on the listener.
         *
         * @param listener the listener.
         * @param event    the event.
         */
        void invoke(JnlpConnectionStateListener listener, JnlpConnectionState event);
    }

    /**
     * The connection state.
     */
    private enum State {
        /**
         * The initial state when created. The {@link JnlpConnectionState} should never be visible to
         * {@link JnlpConnectionStateListener} in this state.
         */
        INITIALIZED,
        /**
         * The state before {@link JnlpConnectionState#fireAfterProperties(Map)}.
         */
        BEFORE_PROPERTIES,
        /**
         * The state once the {@link JnlpConnectionState#getProperties()} are available and before
         * {@link JnlpConnectionState#approve()} or
         * {@link JnlpConnectionState#reject(ConnectionRefusalException)}.
         */
        AFTER_PROPERTIES,
        /**
         * The state once {@link JnlpConnectionState#reject(ConnectionRefusalException)} has been called.
         */
        REJECTED,
        /**
         * The state after {@link JnlpConnectionState#approve()} has been called and before the {@link Channel}
         * has been built.
         */
        APPROVED,
        /**
         * The state before the channel is to be built.
         */
        BEFORE_CHANNEL,
        /**
         * The channel has been built and is probably still open.
         */
        AFTER_CHANNEL,
        /**
         * The channel has been closed but the socket may or may not have been closed yet.
         */
        CHANNEL_CLOSED,
        /**
         * The socket has been closed.
         */
        DISCONNECTED
    }

    /**
     * Marker base class for all stashed state data.
     *
     * @see JnlpConnectionState#setStash(ListenerState)
     * @see JnlpConnectionState#getStash(Class)
     */
    public interface ListenerState {}
}
