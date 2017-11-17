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

import hudson.remoting.Future;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import org.jenkinsci.remoting.util.SettableFuture;

/**
 * Converts the {@link IOHubRegistrationCallback} pattern into a {@link Future}.
 *
 * @since 3.0
 */
class IOHubRegistrationFutureAdapterImpl implements IOHubRegistrationCallback {
    /**
     * The future.
     */
    private final SettableFuture<SelectionKey> future = SettableFuture.create();

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRegistered(SelectionKey selectionKey) {
        future.set(selectionKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClosedChannel(ClosedChannelException e) {
        future.setException(e);
    }

    /**
     * Gets the future.
     *
     * @return the future.
     */
    public SettableFuture<SelectionKey> getFuture() {
        return future;
    }
}
