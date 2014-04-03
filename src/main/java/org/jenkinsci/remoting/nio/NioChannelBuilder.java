package org.jenkinsci.remoting.nio;

import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;

import java.io.IOException;
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
}
