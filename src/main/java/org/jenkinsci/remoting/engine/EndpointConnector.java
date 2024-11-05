package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.remoting.Channel;
import java.io.Closeable;
import java.net.URL;
import java.util.concurrent.Future;

/**
 * Represents a connection to a remote endpoint to open a {@link Channel}.
 */
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
    Boolean waitUntilReady() throws InterruptedException;

    /**
     * @return The name of the protocol used by this connection.
     */
    String getProtocol();

    /**
     * @return the URL of the endpoint, if {@link #waitUntilReady()} returned {@code true}.
     */
    @Nullable
    URL getUrl();
}
