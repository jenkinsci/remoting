package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.remoting.Channel;
import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.Future;

public interface EndpointConnector extends Closeable {

    /**
     * @return a future to the channel to be established. Returns null if the connection cannot be established at all.
     * @throws Exception
     */
    @CheckForNull
    Future<Channel> connect() throws Exception;

    /**
     * Waits until the connection can be established.
     * @return true if the connection is ready, null if the connection never got ready
     * @throws InterruptedException if the thread is interrupted
     */
    @CheckForNull
    Boolean waitForReady() throws InterruptedException;

    /**
     * Name of the protocol used by this connection.
     * @return
     */
    String getProtocol();

    @Override
    default void close() throws IOException {}

    URL getUrl();
}
