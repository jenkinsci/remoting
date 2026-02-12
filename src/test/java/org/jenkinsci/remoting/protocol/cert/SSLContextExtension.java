/*
 * The MIT License
 *
 * Copyright (c) 2016, Stephen Connolly
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public class SSLContextExtension implements BeforeAllCallback, AfterAllCallback {

    private final List<KeyWithChain> keys;

    private final List<X509CertificateExtension> certificates;

    private final String id;
    private SSLContext sslContext;
    private boolean validityChecking;

    public SSLContextExtension() {
        this("");
    }

    public SSLContextExtension(String id) {
        this.keys = new ArrayList<>();
        this.certificates = new ArrayList<>();
        this.id = id;
    }

    private static KeyStore createKeyStore(
            @CheckForNull List<X509CertificateExtension> certificates,
            @CheckForNull List<KeyWithChain> keys,
            @NonNull char[] password)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        int id = 1;
        store.load(null, password);
        if (certificates != null) {
            for (X509CertificateExtension certificate : certificates) {
                store.setCertificateEntry("cert-" + id, certificate.certificate());
                id++;
            }
        }
        if (keys != null) {
            for (KeyWithChain key : keys) {
                Certificate[] chain = new Certificate[key.chain.length];
                for (int i = 0; i < key.chain.length; i++) {
                    chain[i] = key.chain[i].certificate();
                }
                store.setKeyEntry("alias-" + id, key.key.getPrivate(), password, chain);
                id++;
            }
        }
        return store;
    }

    @SafeVarargs
    private static <TYPE, SUBTYPE extends TYPE> SUBTYPE findFirst(Class<SUBTYPE> type, TYPE... options) {
        if (options == null) {
            return null;
        }
        for (TYPE option : options) {
            if (type.isInstance(option)) {
                return type.cast(option);
            }
        }
        return null;
    }

    public SSLContextExtension as(RSAKeyPairExtension key, X509CertificateExtension... chain) {
        this.keys.add(new KeyWithChain(key, chain));
        return this;
    }

    public SSLContextExtension trusting(X509CertificateExtension certificate) {
        this.certificates.add(certificate);
        return this;
    }

    public SSLContextExtension withValidityChecking() {
        this.validityChecking = true;
        return this;
    }

    public SSLContextExtension withoutValidityChecking() {
        this.validityChecking = false;
        return this;
    }

    public SSLContext context() {
        return sslContext;
    }

    public String getProtocol() {
        return sslContext.getProtocol();
    }

    public Provider getProvider() {
        return sslContext.getProvider();
    }

    public SSLSocketFactory getSocketFactory() {
        return sslContext.getSocketFactory();
    }

    public SSLServerSocketFactory getServerSocketFactory() {
        return sslContext.getServerSocketFactory();
    }

    public SSLEngine createSSLEngine() {
        return sslContext.createSSLEngine();
    }

    public SSLEngine createSSLEngine(String s, int i) {
        return sslContext.createSSLEngine(s, i);
    }

    public SSLSessionContext getServerSessionContext() {
        return sslContext.getServerSessionContext();
    }

    public SSLSessionContext getClientSessionContext() {
        return sslContext.getClientSessionContext();
    }

    public SSLParameters getDefaultSSLParameters() {
        return sslContext.getDefaultSSLParameters();
    }

    public SSLParameters getSupportedSSLParameters() {
        return sslContext.getSupportedSSLParameters();
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        sslContext = SSLContext.getInstance("TLS");
        char[] password = "password".toCharArray();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(createKeyStore(null, keys, password), password);
        KeyManager[] keyManagers = kmf.getKeyManagers();

        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(createKeyStore(certificates, null, password));
        TrustManager[] trustManagers = new TrustManager[1];
        trustManagers[0] = validityChecking
                ? new ValidityCheckingX509ExtendedTrustManager(
                        findFirst(X509ExtendedTrustManager.class, tmf.getTrustManagers()))
                : findFirst(X509ExtendedTrustManager.class, tmf.getTrustManagers());

        sslContext.init(keyManagers, trustManagers, null);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        sslContext = null;
    }

    private static class KeyWithChain {
        private final RSAKeyPairExtension key;
        private final X509CertificateExtension[] chain;

        public KeyWithChain(RSAKeyPairExtension key, X509CertificateExtension... chain) {
            this.key = key;
            this.chain = chain;
        }
    }
}
