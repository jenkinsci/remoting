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
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class SSLContextRule implements TestRule {

    private final List<KeyWithChain> keys;

    private final List<X509CertificateRule> certificates;

    private final String id;
    private SSLContext context;
    private boolean validityChecking;

    public SSLContextRule() {
        this("");
    }

    public SSLContextRule(String id) {
        this.keys = new ArrayList<>();
        this.certificates = new ArrayList<>();
        this.id = id;
    }

    private static KeyStore createKeyStore(
            @CheckForNull List<X509CertificateRule> certificates,
            @CheckForNull List<KeyWithChain> keys,
            @NonNull char[] password)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        int id = 1;
        store.load(null, password);
        if (certificates != null) {
            for (X509CertificateRule certificate : certificates) {
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

    public SSLContextRule as(RSAKeyPairRule key, X509CertificateRule... chain) {
        this.keys.add(new KeyWithChain(key, chain));
        return this;
    }

    public SSLContextRule trusting(X509CertificateRule certificate) {
        this.certificates.add(certificate);
        return this;
    }

    public SSLContextRule withValidityChecking() {
        this.validityChecking = true;
        return this;
    }

    public SSLContextRule withoutValidityChecking() {
        this.validityChecking = false;
        return this;
    }

    public SSLContext context() {
        return context;
    }

    public String getProtocol() {
        return context.getProtocol();
    }

    public Provider getProvider() {
        return context.getProvider();
    }

    public SSLSocketFactory getSocketFactory() {
        return context.getSocketFactory();
    }

    public SSLServerSocketFactory getServerSocketFactory() {
        return context.getServerSocketFactory();
    }

    public SSLEngine createSSLEngine() {
        return context.createSSLEngine();
    }

    public SSLEngine createSSLEngine(String s, int i) {
        return context.createSSLEngine(s, i);
    }

    public SSLSessionContext getServerSessionContext() {
        return context.getServerSessionContext();
    }

    public SSLSessionContext getClientSessionContext() {
        return context.getClientSessionContext();
    }

    public SSLParameters getDefaultSSLParameters() {
        return context.getDefaultSSLParameters();
    }

    public SSLParameters getSupportedSSLParameters() {
        return context.getSupportedSSLParameters();
    }

    @Override
    public Statement apply(final Statement base, Description description) {
        Skip skip = description.getAnnotation(Skip.class);
        if (skip != null
                && (skip.value().length == 0 || Arrays.asList(skip.value()).contains(id))) {
            return base;
        }
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                context = SSLContext.getInstance("TLS");
                char[] password = "password".toCharArray();
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(createKeyStore(null, keys, password), password);
                KeyManager[] keyManagers = kmf.getKeyManagers();

                final TrustManagerFactory tmf =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(createKeyStore(certificates, null, password));
                TrustManager[] trustManagers = new TrustManager[1];
                trustManagers[0] = validityChecking
                        ? new ValidityCheckingX509ExtendedTrustManager(
                                findFirst(X509ExtendedTrustManager.class, tmf.getTrustManagers()))
                        : findFirst(X509ExtendedTrustManager.class, tmf.getTrustManagers());

                context.init(keyManagers, trustManagers, null);
                try {
                    base.evaluate();
                } finally {
                    context = null;
                }
            }
        };
    }

    /**
     * Indicate the the rule should be skipped for the annotated tests.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface Skip {
        String[] value() default {};
    }

    private static class KeyWithChain {
        private final RSAKeyPairRule key;
        private final X509CertificateRule[] chain;

        public KeyWithChain(RSAKeyPairRule key, X509CertificateRule... chain) {
            this.key = key;
            this.chain = chain;
        }
    }
}
