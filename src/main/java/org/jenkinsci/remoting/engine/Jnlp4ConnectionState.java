/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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
package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.List;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;

/**
 * Represents the connection state for a {@link JnlpProtocol4Handler} connection.
 *
 * @since 3.0
 */
public class Jnlp4ConnectionState extends JnlpConnectionState {

    /**
     * The client certificate.
     */
    @CheckForNull
    private X509Certificate certificate;

    /**
     * Constructor.
     *
     * @param socket    the {@link Socket}.
     * @param listeners the {@link JnlpConnectionStateListener} instances.
     */
    protected Jnlp4ConnectionState(@NonNull Socket socket, List<? extends JnlpConnectionStateListener> listeners) {
        super(socket, listeners);
    }

    /**
     * Gets the client certificate (if available).
     * @return the client certificate (if available).
     */
    @CheckForNull
    public X509Certificate getCertificate() {
        return certificate;
    }

    /**
     * Advances the connection state to indicate that a connection has been "secured" and the property exchange
     * is about to take place.
     *
     * @param certificate the client certificate.
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
