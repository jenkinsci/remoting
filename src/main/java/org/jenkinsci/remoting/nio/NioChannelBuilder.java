package org.jenkinsci.remoting.nio;

import hudson.remoting.Capability;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.ClassFilter;
import hudson.remoting.JarCache;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.ExecutorService;

/**
 * {@link ChannelBuilder} subtype for {@link NioChannelHub}.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.38
 * @see NioChannelHub#newChannelBuilder(String, ExecutorService)
 */
public abstract class NioChannelBuilder extends ChannelBuilder {
    /*package*/ SelectableChannel /* & ReadableByteChannel&WritableByteChannel */ r, w;

    NioChannelBuilder(String name, ExecutorService executors) {
        super(name, executors);
    }

    @Override
    public Channel build(SocketChannel socket) throws IOException {
        this.r = socket;
        this.w = socket;
        return super.build(socket);
    }

    public Channel build(SelectableChannel r, SelectableChannel w) throws IOException {
        this.r = r;
        this.w = w;
        return super.build(
                Channels.newInputStream((ReadableByteChannel) r), Channels.newOutputStream((WritableByteChannel) w));
    }

    @Override
    public Channel build(Socket s) throws IOException {
        SocketChannel ch = s.getChannel();
        if (ch == null) {
            throw new IllegalArgumentException(s + " doesn't have a channel");
        }
        return build(ch);
    }

    @Override
    public NioChannelBuilder withBaseLoader(ClassLoader base) {
        return (NioChannelBuilder) super.withBaseLoader(base);
    }

    @Override
    public NioChannelBuilder withMode(Channel.Mode mode) {
        return (NioChannelBuilder) super.withMode(mode);
    }

    @Override
    public NioChannelBuilder withCapability(Capability capability) {
        return (NioChannelBuilder) super.withCapability(capability);
    }

    @Override
    public NioChannelBuilder withHeaderStream(OutputStream header) {
        return (NioChannelBuilder) super.withHeaderStream(header);
    }

    @Override
    public NioChannelBuilder withRestricted(boolean restricted) {
        return (NioChannelBuilder) super.withRestricted(restricted);
    }

    @Override
    public NioChannelBuilder withJarCache(JarCache jarCache) {
        return (NioChannelBuilder) super.withJarCache(jarCache);
    }

    @Override
    public NioChannelBuilder withoutJarCache() {
        return (NioChannelBuilder) super.withoutJarCache();
    }

    @Override
    public NioChannelBuilder withClassFilter(ClassFilter filter) {
        return (NioChannelBuilder) super.withClassFilter(filter);
    }
}
