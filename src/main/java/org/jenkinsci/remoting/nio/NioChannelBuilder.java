package org.jenkinsci.remoting.nio;

import hudson.remoting.Capability;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Mode;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.JarCache;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

/**
 * @author Kohsuke Kawaguchi
 */
public class NioChannelBuilder extends ChannelBuilder {
    /*package*/ SelectableChannel/* & ReadableByteChannel&WritableByteChannel */ r,w;

    public NioChannelBuilder(String name, ExecutorService executors) {
        super(name, executors);
    }

    public Channel build(SocketChannel socket) throws IOException {
        this.r = socket;
        this.w = socket;
        return super.build(socket.socket());
    }

    @Override
    public Channel build(Socket s) throws IOException {
        return build(s.getChannel());
    }


    @Override
    public NioChannelBuilder withBaseLoader(ClassLoader base) {
        return (NioChannelBuilder)super.withBaseLoader(base);
    }

    @Override
    public NioChannelBuilder withMode(Mode mode) {
        return (NioChannelBuilder)super.withMode(mode);
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
}
