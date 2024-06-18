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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.remoting.AbstractByteBufferCommandTransport;
import hudson.remoting.BinarySafeStream;
import hudson.remoting.Capability;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.ChannelClosedException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.ObjectOutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.jenkinsci.remoting.engine.JnlpConnectionState;
import org.jenkinsci.remoting.protocol.ApplicationLayer;
import org.jenkinsci.remoting.util.AnonymousClassWarnings;
import org.jenkinsci.remoting.util.ByteBufferUtils;
import org.jenkinsci.remoting.util.SettableFuture;
import org.jenkinsci.remoting.util.ThrowableUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * An {@link ApplicationLayer} that produces a {@link Channel}.
 *
 * @since 3.0
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
    private final Listener listener;

    private String cookie;

    /**
     * Creates a new {@link ChannelApplicationLayer}
     *
     * @param executorService the {@link ExecutorService} to use for the {@link Channel}.
     * @param listener the {@link Listener} to notify when the {@link Channel} is available.
     */
    public ChannelApplicationLayer(@NonNull ExecutorService executorService, @CheckForNull Listener listener) {
        this.executorService = executorService;
        this.listener = listener;
    }

    /**
     * Creates a new {@link ChannelApplicationLayer}
     *
     * @param executorService the {@link ExecutorService} to use for the {@link Channel}.
     * @param listener the {@link Listener} to notify when the {@link Channel} is available.
     * @param cookie a cookie to pass through the channel.
     */
    @Restricted(NoExternalUse.class)
    public ChannelApplicationLayer(
            @NonNull ExecutorService executorService, @CheckForNull Listener listener, String cookie) {
        this.executorService = executorService;
        this.listener = listener;
        this.cookie = cookie;
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
    public void onRead(@NonNull ByteBuffer data) throws IOException {
        if (!futureChannel.isDone()) {
            assert channel == null && transport == null && capabilityLength != null;
            if (capabilityLength.hasRemaining()) {
                ByteBufferUtils.put(data, capabilityLength);
                if (capabilityLength.hasRemaining()) {
                    return;
                }
                capabilityContent =
                        ByteBuffer.allocate(((capabilityLength.get(0) & 0xff) << 8) + (capabilityLength.get(1) & 0xff));
            }
            assert capabilityContent != null;
            if (capabilityContent.hasRemaining()) {
                ByteBufferUtils.put(data, capabilityContent);
                if (capabilityContent.hasRemaining()) {
                    return;
                }
                byte[] capabilityBytes = new byte[capabilityContent.capacity()];
                ((Buffer) capabilityContent).flip();
                capabilityContent.get(capabilityBytes);
                if (capabilityContent.hasRemaining()) {
                    return;
                }
                final Capability remoteCapability = Capability.read(new ByteArrayInputStream(capabilityBytes));
                transport = new ByteBufferCommandTransport(remoteCapability);
                try {
                    ChannelBuilder builder =
                            new ChannelBuilder(stack().name(), executorService).withMode(Channel.Mode.BINARY);
                    if (listener instanceof ChannelDecorator) {
                        channel = decorate(((ChannelDecorator) listener).decorate(builder))
                                .build(transport);
                    } else {
                        channel = decorate(builder).build(transport);
                    }
                } catch (IOException e) {
                    try {
                        doCloseWrite();
                    } catch (IOException suppressed) {
                        ThrowableUtils.chain(e, suppressed);
                    }
                    transport = null;
                    futureChannel.setException(e);
                    throw e;
                }
                if (cookie != null) {
                    Objects.requireNonNull(channel).setProperty(JnlpConnectionState.COOKIE_KEY, cookie);
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
                InterruptedIOException ie = new InterruptedIOException();
                ie.bytesTransferred = data.remaining();
                ((Buffer) data).position(data.limit());
                Thread.currentThread().interrupt();
                throw ie;
            } catch (ExecutionException e) {
                // should never get here as futureChannel.isDone(), but just in case we do throw away
                ((Buffer) data).position(data.limit()); // dump any remaining data as nobody will ever receive it
                throw new IOException(e);
            }
        }
        assert channel != null && transport != null : "If futureChannel.isDone() then we have a channel and transport";
        try {
            transport.receive(data);
        } catch (IOException e) {
            channel.terminate(e); // we are done if there is an I/O error
            ((Buffer) data).position(data.limit()); // dump any remaining data as nobody will ever receive it
            throw e;
        } catch (InterruptedException e) {
            // if the channel receive was interrupted we cannot guarantee that the partial state has been correctly
            // stored, thus we cannot trust the channel instance any more and it needs to be closed.
            InterruptedIOException reason = new InterruptedIOException();
            reason.bytesTransferred = data.remaining();
            channel.terminate(reason);
            ((Buffer) data).position(data.limit()); // dump any remaining data as nobody will ever receive it
            Thread.currentThread().interrupt();
            throw reason;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onReadClosed(IOException cause) {
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
    public void start() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos =
                    AnonymousClassWarnings.checkingObjectOutputStream(BinarySafeStream.wrap(bos))) {
                oos.writeObject(new Capability());
            }
            ByteBuffer buffer = ByteBufferUtils.wrapUTF8(bos.toString(StandardCharsets.US_ASCII));
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
        void onChannel(@NonNull Channel channel);
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
        @NonNull
        ChannelBuilder decorate(@NonNull ChannelBuilder builder);
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
            super(true);
            this.remoteCapability = remoteCapability;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected void write(ByteBuffer headerAndData) throws IOException {
            // TODO: Any way to get channel information here
            if (isWriteOpen()) {
                try {
                    ChannelApplicationLayer.this.write(headerAndData);
                } catch (ClosedChannelException e) {
                    // Probably it should be another exception type at all
                    throw new ChannelClosedException(
                            null,
                            "Protocol stack cannot write data anymore. ChannelApplicationLayer reports that the NIO Channel is closed",
                            e);
                }
            } else {
                throw new ChannelClosedException(
                        null, "Protocol stack cannot write data anymore. It is not open for write", null);
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
        public Capability getRemoteCapability() {
            return remoteCapability;
        }
    }
}
