/*
 * The MIT License
 *
 * Copyright (c) 2016, Stephen Connolly, CloudBees, Inc.
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
package org.jenkinsci.remoting.protocol.impl;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import hudson.remoting.AbstractByteBufferCommandTransport;
import hudson.remoting.BinarySafeStream;
import hudson.remoting.Capability;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.ChannelClosedException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import javax.annotation.Nullable;
import org.jenkinsci.remoting.protocol.ApplicationLayer;
import org.jenkinsci.remoting.util.ByteBufferUtils;
import org.jenkinsci.remoting.util.SettableFuture;
import org.jenkinsci.remoting.util.ThrowableUtils;

/**
 * An {@link ApplicationLayer} that produces a {@link Channel}.
 *
 * @since FIXME
 */
public class ChannelApplicationLayer extends ApplicationLayer<Future<Channel>> {

    /**
     * The {@link ExecutorService} to use for the {@link Channel}.
     */
    private final ExecutorService executorService;
    /**
     * We cannot instantiate a {@link Channel} until we know the remote {@link Capability}, so this {@link Future}
     * is used to enable the async creation of the {@link Channel} once the remote {@link Capability} has been received.
     */
    private final SettableFuture<Channel> futureChannel = SettableFuture.create();
    /**
     * The transport used by the {@link Channel} or {@code null} if we have not completed {@link Capability} exchange.
     */
    @Nullable
    private AbstractByteBufferCommandTransport transport;
    /**
     * The {@link Channel} or {@code null} if we have not completed {@link Capability} exchange.
     */
    @CheckForNull
    private Channel channel;
    /**
     * Buffer to receive the expected length of the serialized remote {@link Capability}.
     */
    private ByteBuffer capabilityLength = ByteBuffer.allocate(2);
    /**
     * Buffer to receive the the serialized remote {@link Capability}.
     */
    private ByteBuffer capabilityContent;
    /**
     * Listener to notify when the {@link Channel} is connected.
     */
    private Listener listener;

    /**
     * Creates a new {@link ChannelApplicationLayer}
     *
     * @param executorService the {@link ExecutorService} to use for the {@link Channel}.
     * @param listener the {@link Listener} to notify when the {@link Channel} is available.
     */
    public ChannelApplicationLayer(@Nonnull ExecutorService executorService,
                                   @CheckForNull Listener listener) {
        this.executorService = executorService;
        this.listener = listener;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Future<Channel> get() {
        return futureChannel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isReadOpen() {
        return channel == null || !channel.isInClosed();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRead(@Nonnull ByteBuffer data) throws IOException {
        if (!futureChannel.isDone()) {
            assert channel == null && transport == null && capabilityLength != null;
            if (capabilityLength.hasRemaining()) {
                ByteBufferUtils.put(data, capabilityLength);
                if (capabilityLength.hasRemaining()) {
                    return;
                }
                capabilityContent = ByteBuffer
                        .allocate(((capabilityLength.get(0) & 0xff) << 8) + (capabilityLength.get(1) & 0xff));
            }
            assert capabilityContent != null;
            if (capabilityContent.hasRemaining()) {
                ByteBufferUtils.put(data, capabilityContent);
                if (capabilityContent.hasRemaining()) {
                    return;
                }
                byte[] capabilityBytes = new byte[capabilityContent.capacity()];
                capabilityContent.flip();
                capabilityContent.get(capabilityBytes);
                if (capabilityContent.hasRemaining()) {
                    return;
                }
                final Capability remoteCapability = Capability.read(new ByteArrayInputStream(capabilityBytes));
                transport = new ByteBufferCommandTransport(remoteCapability);
                try {
                    ChannelBuilder builder = new ChannelBuilder(stack().name(), executorService)
                            .withMode(Channel.Mode.BINARY);
                    if (listener instanceof ChannelDecorator) {
                        channel = decorate(((ChannelDecorator) listener).decorate(builder)).build(transport);
                    } else {
                        channel = decorate(builder).build(transport);
                    }
                } catch (IOException e) {
                    try {
                        doCloseWrite();
                    } catch (IOException suppressed) {
                        ThrowableUtils.addSuppressed(e, suppressed);
                    }
                    transport = null;
                    futureChannel.setException(e);
                    throw e;
                }
                futureChannel.set(channel);
                capabilityContent = null;
                capabilityLength = null;
                if (listener != null) {
                    listener.onChannel(channel);
                }
            }
        }
        if (channel == null) {
            assert futureChannel.isDone();
            try {
                channel = futureChannel.get();
            } catch (InterruptedException e) {
                // should never get here as futureChannel.isDone(), but just in case we do throw away
                data.position(data.limit()); // dump any remaining data as nobody will ever receive it
                throw new IOException(e);
            } catch (ExecutionException e) {
                data.position(data.limit()); // dump any remaining data as nobody will ever receive it
                throw new IOException(e);
            }
        }
        assert channel != null && transport != null : "If futureChannel.isDone() then we have a channel and transport";
        try {
            transport.receive(data);
        } catch (IOException e) {
            channel.terminate(e); // we are done if there is an I/O error
            data.position(data.limit()); // dump any remaining data as nobody will ever receive it
            throw e;
        } catch (InterruptedException e) {
            // if the channel receive was interrupted we cannot guarantee that the partial state has been correctly
            // stored, thus we cannot trust the channel instance any more and it needs to be closed.
            IOException reason = new IOException(e);
            channel.terminate(reason);
            data.position(data.limit()); // dump any remaining data as nobody will ever receive it
            throw reason;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onReadClosed(IOException cause) throws IOException {
        if (futureChannel.isDone()) {
            if (channel != null) {
                channel.terminate(cause == null ? new ClosedChannelException() : cause);
            }
        } else {
            futureChannel.setException(cause == null ? new ClosedChannelException() : cause);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws IOException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(BinarySafeStream.wrap(bos));
            try {
                oos.writeObject(new Capability());
            } finally {
                oos.close();
            }
            ByteBuffer buffer = ByteBufferUtils.wrapUTF8(bos.toString("US-ASCII"));
            write(buffer);
        } catch (IOException e) {
            futureChannel.setException(e);
        }
    }

    /**
     * Allows subclasses to decorate the {@link ChannelBuilder}
     *
     * @param builder the {@link ChannelBuilder} to decorate
     * @return the provided {@link ChannelBuilder} for method chaining.
     */
    public ChannelBuilder decorate(ChannelBuilder builder) {
        return builder;
    }

    /**
     * Callback interface for notification of the {@link Channel} being created.
     *
     * @see ChannelDecorator to be able to customize the creation of the channel.
     */
    public interface Listener {
        /**
         * Called when the {@link Channel} has been constructed.
         * @param channel the {@link Channel}.
         */
        void onChannel(@Nonnull Channel channel);
    }

    /**
     * Callback for decorating the {@link ChannelBuilder} before the {@link Channel} is created.
     */
    public interface ChannelDecorator extends Listener {
        /**
         * Decorate the {@link ChannelBuilder}
         *
         * @param builder the {@link ChannelBuilder} to decorate
         * @return the provided {@link ChannelBuilder} for method chaining.
         */
        @Nonnull
        ChannelBuilder decorate(@Nonnull ChannelBuilder builder);
    }

    /**
     * The actual {@link AbstractByteBufferCommandTransport}.
     */
    private class ByteBufferCommandTransport extends AbstractByteBufferCommandTransport {
        /**
         * The remote capability.
         */
        private final Capability remoteCapability;

        /**
         * Our constructor.
         * @param remoteCapability the remote capability
         */
        public ByteBufferCommandTransport(Capability remoteCapability) {
            this.remoteCapability = remoteCapability;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void write(ByteBuffer header, ByteBuffer data) throws IOException {
            if (isWriteOpen()) {
                try {
                    ChannelApplicationLayer.this.write(header);
                    ChannelApplicationLayer.this.write(data);
                } catch (ClosedChannelException e) {
                    throw new ChannelClosedException(e);
                }
            } else {
                throw new ChannelClosedException(new ClosedChannelException());
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void closeWrite() throws IOException {
            doCloseWrite();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void closeRead() throws IOException {
            doCloseRead();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Capability getRemoteCapability() throws IOException {
            return remoteCapability;
        }
    }
}
