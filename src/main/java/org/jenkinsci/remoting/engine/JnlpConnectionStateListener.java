package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.NonNull;

public abstract class JnlpConnectionStateListener {
    /**
     * Notification that the client connection has been established and
     *
     * @param event
     */
    public void beforeProperties(@NonNull JnlpConnectionState event) {

    }

    public abstract void afterProperties(@NonNull JnlpConnectionState event);

    public void beforeChannel(@NonNull JnlpConnectionState event) {
    }

    public abstract void afterChannel(@NonNull JnlpConnectionState event);

    public void channelClosed(@NonNull JnlpConnectionState event) {

    }

    public void afterDisconnect(@NonNull JnlpConnectionState event) {

    }
}
