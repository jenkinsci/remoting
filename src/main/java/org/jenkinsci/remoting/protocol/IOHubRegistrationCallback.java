/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc., Stephen Connolly
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
package org.jenkinsci.remoting.protocol;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

/**
 * Callback to be notified when a call to
 * {@link IOHub#register(SelectableChannel, IOHubReadyListener, boolean, boolean, boolean, boolean, IOHubRegistrationCallback)} has completed registration.
 *
 * @since 3.0
 */
public interface IOHubRegistrationCallback {

    /**
     * Notification callback that the {@link SelectableChannel} has been successfully registered.
     *
     * @param selectionKey the {@link SelectionKey} that the {@link SelectableChannel} was registered as.
     */
    void onRegistered(SelectionKey selectionKey);

    /**
     * Notification callback that the {@link SelectableChannel} was closed by the time the registration request was
     * processed.
     *
     * @param e the {@link ClosedChannelException} that was thrown when the registration request was attempted.
     */
    void onClosedChannel(ClosedChannelException e);
}
