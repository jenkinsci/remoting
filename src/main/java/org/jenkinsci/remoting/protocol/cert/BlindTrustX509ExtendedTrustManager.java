/*
 * The MIT License
 *
 * Copyright (c) 2016, Stephen Connolly, CloudBees, Inc.
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
package org.jenkinsci.remoting.protocol.cert;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

/**
 * An {@link X509ExtendedTrustManager} that trusts everything always.
 *
 * @since FIXME
 */
@IgnoreJRERequirement // TODO We override some methods in Java 7, so remove this ignore when baseline is Java 7
public class BlindTrustX509ExtendedTrustManager extends X509ExtendedTrustManager {
    /**
     * The Javadoc for {@link X509ExtendedTrustManager} mandates that an {@link IllegalArgumentException} be thrown
     * if the {@code authType} is {@code null} or zero-length.
     *
     * @param authType the {@code authType}.
     */
    private static void validateAuthType(String authType) {
        if (authType == null) {
            throw new IllegalArgumentException("authType must not be null"); // per javadoc specification
        }
        if (authType.isEmpty()) {
            throw new IllegalArgumentException("authType must not be zero-length"); // per javadoc specification
        }
    }

    /**
     * The Javadoc for {@link X509ExtendedTrustManager} mandates that an {@link IllegalArgumentException} be thrown
     * if the {@code chain} is {@code null} or zero-length.
     *
     * @param chain the {@code chain}.
     */
    private static void validateChain(X509Certificate[] chain) {
        if (chain == null) {
            throw new IllegalArgumentException("chain must not be null"); // per javadoc specification
        }
        if (chain.length == 0) {
            throw new IllegalArgumentException("chain must not be zero-length"); // per javadoc specification
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
