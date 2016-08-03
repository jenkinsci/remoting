package org.jenkinsci.remoting.engine;

import java.io.IOException;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;

public class Jnlp3ConnectionState extends LegacyJnlpConnectionState {

    private ChannelCiphers channelCiphers;
    private String newCookie;

    protected Jnlp3ConnectionState(@Nonnull Socket socket,
                                   List<? extends JnlpConnectionStateListener> listeners) throws IOException {
        super(socket, listeners);
    }

    public ChannelCiphers getChannelCiphers() {
        return channelCiphers;
    }

    public String getNewCookie() {
        return newCookie;
    }

    /*package*/ void setChannelCiphers(ChannelCiphers channelCiphers) {
        this.channelCiphers = channelCiphers;
    }

    /*package*/ void setNewCookie(String newCookie) {
        this.newCookie = newCookie;
    }
}
