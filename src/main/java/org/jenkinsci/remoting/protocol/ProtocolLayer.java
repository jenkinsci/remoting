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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.jenkinsci.remoting.util.ByteBufferUtils;

/**
 * A network {@link ProtocolStack} consists of a number of {@link ProtocolLayer}s. This interface represents the general
 * contract of all layers in the stack.
 *
 * @since 3.0
 */
public interface ProtocolLayer {

    /**
     * A handy constant to use for no-op send/receive calls.
     */
    ByteBuffer EMPTY_BUFFER = ByteBufferUtils.EMPTY_BUFFER;

    /**
     * Initializes the layer with its {@link ProtocolStack.Ptr}. All lower layers in the stack will be initialized
     * before a call to this method. All layers in a stack will be initialized before a call to {@link #start()}.
     *
     * @param ptr the position of this layer in the stack.
     * @throws IOException if something goes wrong.
     */
    void init(@NonNull ProtocolStack<?>.Ptr ptr) throws IOException;

    /**
     * Starts this layer. All layers in the stack will be initialized before a call to this method. All lower layers
     * in the stack will have been started before this layer is started.
     *
     * @throws IOException if something goes wrong.
     */
    void start() throws IOException;

    /**
     * Interface to indicate that this layer receives data from lower layers.
     *
     * @since 3.0
     */
    interface Recv extends ProtocolLayer {
        /**
         * Callback on data being received from the lower layer.
         *
         * @param data the data received. Any data consumed from the {@link ByteBuffer} can be assumed as processed.
         *             Any data not consumed from the {@link ByteBuffer} will be the responsibility of the caller
         *             to resubmit in subsequent calls.
         * @throws IOException if there was an error during processing of the received data.
         */
        void onRecv(@NonNull ByteBuffer data) throws IOException;

        /**
         * Callback on the lower layer's source of data being closed.
         *
         * @param cause the cause of the lower layer being closed or {@code null}.
         * @throws IOException if there was an error during the processing of the close notification.
         */
        void onRecvClosed(@CheckForNull IOException cause) throws IOException;

        /**
         * Tracks if this layer is accepting received data via {@link #onRecv(ByteBuffer)}.
         * Once this method returns {@code false} it must always return {@code false} and can be assumed to behave in
         * this way.
         *
         * @return {@code true} if accepting received data via {@link #onRecv(ByteBuffer)}.
         */
        boolean isRecvOpen();
    }

    /**
     * Interface to indicate that this layer sends data to lower layers.
     *
     * @since 3.0
     */
    interface Send extends ProtocolLayer {
        /**
         * Sends data to the lower layer.
         *
         * @param data the data to send. Any data consumed from the {@link ByteBuffer} can be assumed as processed.
         *             Any data not consumed from the {@link ByteBuffer} will be the responsibility of the caller
         *             to resubmit in subsequent calls.
         * @throws IOException if there was an error during processing of the data.
         */
        void doSend(@NonNull ByteBuffer data) throws IOException;

        /**
         * Notify the lower layer that it should close. Callers to this method are assumed to have already called
         * {@link Recv#onRecvClosed(IOException)} for any upper layers.
         *
         * @throws IOException if there was an error closing the lower layer.
         */
        void doCloseSend() throws IOException;

        /**
         * Tracks if this layer is submitting data to be sent via {@link #doSend(ByteBuffer)}.
         * Once this method returns {@code false} it must always return {@code false} and can be assumed to behave in
         * this way.
         *
         * @return {@code true} if submitting data to be sent via {@link #doSend(ByteBuffer)}.
         */
        boolean isSendOpen();
    }
}
