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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import java.net.Socket;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;

/**
 * A listener for connection state changes.
 *
 * The listeners will be called in order for any unique event. Use
 * {@link JnlpConnectionState#setStash(JnlpConnectionState.ListenerState)} and
 * {@link JnlpConnectionState#getStash(Class)} if you need to maintain state between callbacks.
 *
 * <ol>
 * <li>{@link #beforeProperties(JnlpConnectionState)} (if closed then {@link #afterDisconnect(JnlpConnectionState)})
 * </li>
 * <li>{@link #afterProperties(JnlpConnectionState)} (if closed then {@link #afterDisconnect(JnlpConnectionState)})</li>
 * <li>{@link #beforeChannel(JnlpConnectionState)} (if closed then {@link #afterDisconnect(JnlpConnectionState)})</li>
 * <li>{@link #afterChannel(JnlpConnectionState)} (if closed then {@link #channelClosed(JnlpConnectionState)})</li>
 * <li>{@link #channelClosed(JnlpConnectionState)}</li>
 * <li>{@link #afterDisconnect(JnlpConnectionState)}</li>
 * </ol>
 *
 * @since 3.0
 */
public abstract class JnlpConnectionStateListener {
    /**
     * Notification that the connection has been established and properties will be exchanged.
     * Call {@link JnlpConnectionState#ignore()} to suppress any further notifications of this event.
     * Call {@link JnlpConnectionState#reject(ConnectionRefusalException)} to reject the connection.
     * Call {@link JnlpConnectionState#approve()} to declare ownership of this event (normally better to defer this to
     * {@link #afterProperties(JnlpConnectionState)})
     *
     * @param event the event.
     */
    public void beforeProperties(@NonNull JnlpConnectionState event) {}

    /**
     * Notification that properties have been exchanged.
     * Call {@link JnlpConnectionState#ignore()} to suppress any further notifications of this event.
     * Call {@link JnlpConnectionState#reject(ConnectionRefusalException)} to reject the connection.
     * Call {@link JnlpConnectionState#approve()} to declare ownership of this event.
     *
     * @param event the event.
     */
    public abstract void afterProperties(@NonNull JnlpConnectionState event);

    /**
     * Callback to allow the {@link JnlpConnectionState#approve()} listener to decorate the {@link ChannelBuilder} via
     * {@link JnlpConnectionState#getChannelBuilder()}.
     *
     * @param event the event.
     */
    public void beforeChannel(@NonNull JnlpConnectionState event) {}

    /**
     * Callback to notify the {@link JnlpConnectionState#approve()} listener that the {@link Channel} has been
     * created and is available via {@link JnlpConnectionState#getChannel()}.
     *
     * @param event the event.
     */
    public abstract void afterChannel(@NonNull JnlpConnectionState event);

    /**
     * Callback to notify the {@link JnlpConnectionState#approve()} listener that the {@link Channel} has been
     * closed.
     *
     * @param event the event.
     */
    public void channelClosed(@NonNull JnlpConnectionState event) {}

    /**
     * Callback to notify the {@link JnlpConnectionState#approve()} listener that the {@link Socket} has been
     * closed.
     *
     * @param event the event.
     */
    public void afterDisconnect(@NonNull JnlpConnectionState event) {}
}
