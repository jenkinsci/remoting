package org.jenkinsci.remoting.nio;

import hudson.remoting.ChannelBuilder;

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

    public NioChannelBuilder withSocket(SocketChannel socket) {
        this.r = socket;
        this.w = socket;
        return this;
    }

    public NioChannelBuilder withSocket(Socket socket) {
        return withSocket(socket.getChannel());
    }
}
