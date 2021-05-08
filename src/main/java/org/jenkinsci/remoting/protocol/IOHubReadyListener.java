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

import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;

/**
 * Interface for receiving callbacks when a {@link SelectableChannel} is ready on its registered
 * {@link SelectionKey#interestOps()}.
 *
 * @see IOHub#register(SelectableChannel, IOHubReadyListener, boolean, boolean, boolean, boolean,
 * IOHubRegistrationCallback)
 * @see IOHub#addInterestAccept(SelectionKey)
 * @see IOHub#addInterestConnect(SelectionKey)
 * @see IOHub#addInterestRead(SelectionKey)
 * @see IOHub#addInterestWrite(SelectionKey)
 * @see IOHub#removeInterestAccept(SelectionKey)
 * @see IOHub#removeInterestConnect(SelectionKey)
 * @see IOHub#removeInterestRead(SelectionKey)
 * @see IOHub#removeInterestWrite(SelectionKey)
 * @see IOHub#unregister(SelectableChannel)
 *
 * @since 3.0
 */
public interface IOHubReadyListener {
    /**
     * Callback to indicate the {@link SelectableChannel} that this listener was registered for is ready for the
     * indicated operations. The {@link SelectionKey#interestOps()} will have been cleared for all the operations
     * that are {@code true} so the callback can be assured that processing of any one specific operation will
     * be linearized, though there may be concurrent calls to ready() with
     * disjoint ready operations. The callback will most likely want to re-register for
     * {@link IOHub#addInterestRead(SelectionKey)} or {@link IOHub#addInterestAccept(SelectionKey)} immediately before
     * returning from a ready notification of the read or accept status. The callback will only want to re-register
     * for a {@link IOHub#addInterestWrite(SelectionKey)} if it has filled the {@link SelectableChannel}'s output
     * buffer and has more data to write.
     *
     * @param accept  if the {@link SelectableChannel} has a connection to accept. Call
     *                {@link IOHub#addInterestAccept(SelectionKey)} to request additional callbacks for this ready
     *                state.
     * @param connect if the {@link SelectableChannel} has established a connection. Call
     *                {@link IOHub#addInterestConnect(SelectionKey)} to request additional callbacks for this ready
     *                state.
     * @param read    if the {@link SelectableChannel} has data available to read. Call
     *                {@link IOHub#addInterestRead(SelectionKey)} to request additional callbacks for this ready state.
     * @param write   if the {@link SelectableChannel} can accept data for writing. Call
     *                {@link IOHub#addInterestWrite(SelectionKey)} to request additional callbacks for this ready state.
     */
    void ready(boolean accept, boolean connect, boolean read, boolean write);
}
