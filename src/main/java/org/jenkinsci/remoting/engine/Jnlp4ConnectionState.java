package org.jenkinsci.remoting.engine;

import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.annotation.Nonnull;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;

public class Jnlp4ConnectionState extends JnlpConnectionState {

    private X509Certificate certificate;

    protected Jnlp4ConnectionState(@Nonnull Socket socket,
                                   List<? extends JnlpConnectionStateListener> listeners) {
        super(socket, listeners);
    }

    public X509Certificate getCertificate() {
        return certificate;
    }

    /**
     * Advances the connection state to indicate that a connection has been "secured" and the property exchange
     * is about to take place.
     *
     * @throws ConnectionRefusalException if the connection has been refused.
     */
    void fireBeforeProperties(X509Certificate certificate) throws ConnectionRefusalException {
        if (this.certificate != null) {
            throw new IllegalStateException("fireBeforeProperties has been called already");
        }
        this.certificate = certificate;
        try {
            super.fireBeforeProperties();
        } catch (IllegalStateException e) {
            // undo the setting of the certificate
            this.certificate = null;
            throw e;
        }
    }
}
