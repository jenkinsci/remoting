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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.Socket;
import java.security.Key;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import net.jcip.annotations.GuardedBy;
import org.jenkinsci.remoting.util.KeyUtils;

/**
 * An {@link X509ExtendedTrustManager} that trusts any chain where the initial certificate was issued for a specific
 * set of trusted {@link PublicKey}s.
 *
 * @since 3.0
 */
public class PublicKeyMatchingX509ExtendedTrustManager extends X509ExtendedTrustManager {

    /**
     * The set of {@link PublicKey}s that are trusted by this {@link TrustManager}. (We use a list because most
     * {@link Key} implementations do not override {@link Object#equals(Object)} and thus we have to iterate and
     * compare using {@link KeyUtils#equals(Key, Key)}.
     */
    @GuardedBy("self")
    private final List<PublicKey> publicKeys;

    /**
     * If {@code true} then the public key of the first certificate in the chain must match one of the keys in
     * {@link #publicKeys}, if {@code false} then in the event of {@link #publicKeys} being empty any certificate
     * chain from a client will be trusted.
     */
    private final boolean strictClient;

    /**
     * If {@code true} then the public key of the first certificate in the chain must match one of the keys in
     * {@link #publicKeys}, if {@code false} then in the event of {@link #publicKeys} being empty any certificate
     * chain from a server will be trusted.
     */
    private final boolean strictServer;

    /**
     * Creates a {@link TrustManager} that will only trust certificate chains where the first certificate's
     * {@link X509Certificate#getPublicKey()} is in the list of trusted public keys.
     *
     * @param publicKeys the initial list of trusted public keys.
     */
    public PublicKeyMatchingX509ExtendedTrustManager(PublicKey... publicKeys) {
        this(true, true, publicKeys);
    }

    /**
     * Creates a {@link TrustManager} that will only trust certificate chains where the first certificate's
     * {@link X509Certificate#getPublicKey()} is in the list of trusted public keys. The {@link #strictClient}
     * and {@link #strictServer} options are useful when establishing trust between two unknown systems and
     * encryption is required before the initial trust can be established and the list of trusted keys populated.
     *
     * @param strictClient set this to {@code false} if you want to accept connections from clients before you have
     *                     trusted any public keys.
     * @param strictServer set this to {@code false} if you want to connect to servers before you
     *                     have trusted any public keys.
     * @param publicKeys   the initial list of trusted public keys.
     */
    public PublicKeyMatchingX509ExtendedTrustManager(
            boolean strictClient, boolean strictServer, PublicKey... publicKeys) {
        this.publicKeys = new ArrayList<>(Arrays.asList(publicKeys));
        this.strictClient = strictClient;
        this.strictServer = strictServer;
    }

    /**
     * Adds a trusted {@link PublicKey}.
     *
     * @param publicKey the key to trust.
     * @return {@code true} if this instance did not already trust the specified public key
     */
    public boolean add(@NonNull PublicKey publicKey) {
        synchronized (publicKeys) {
            for (PublicKey k : publicKeys) {
                if (KeyUtils.equals(publicKey, k)) {
                    return false;
                }
            }
            publicKeys.add(publicKey);
            return true;
        }
    }

    /**
     * Removes a trusted {@link PublicKey}.
     *
     * @param publicKey the key to trust.
     * @return {@code true} if this instance trusted the specified public key
     */
    public boolean remove(PublicKey publicKey) {
        synchronized (publicKeys) {
            for (Iterator<PublicKey> iterator = publicKeys.iterator(); iterator.hasNext(); ) {
                PublicKey k = iterator.next();
                if (KeyUtils.equals(publicKey, k)) {
                    iterator.remove();
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Check if a {@link PublicKey} is trusted.
     *
     * @param publicKey the key to check.
     * @return {@code true} if this instance trusts the specified public key.
     */
    public boolean isTrusted(PublicKey publicKey) {
        synchronized (publicKeys) {
            for (PublicKey k : publicKeys) {
                if (KeyUtils.equals(publicKey, k)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Clears the trusted public keys.
     */
    public void clear() {
        synchronized (publicKeys) {
            publicKeys.clear();
        }
    }

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
     * Checks that the chain is trusted.
     *
     * @param chain the chain to check.
     * @throws CertificateException if the chain is not trusted.
     */
    private void checkPublicKey(boolean client, X509Certificate[] chain) throws CertificateException {
        PublicKey chainKey = chain[0].getPublicKey();
        byte[] chainKeyEncoded = chainKey.getEncoded();
        if (chainKeyEncoded == null) {
            throw new CertificateException(String.format(
                    "Public key of the first certificate in chain (subject: '%s') "
                            + "(algorithm: '%s'; format: '%s') does not support binary encoding",
                    chain[0].getSubjectDN(), chainKey.getAlgorithm(), chainKey.getFormat()));
        }
        synchronized (publicKeys) {
            if (publicKeys.isEmpty() ? (client ? !strictClient : !strictServer) : isTrusted(chainKey)) {
                return;
            }
        }
        throw new CertificateException(String.format(
                "Public key of the first certificate in chain (subject: %s) " + "is not in the list of trusted keys",
                chain[0].getSubjectDN()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
        checkPublicKey(true, chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
        checkPublicKey(false, chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
        checkPublicKey(true, chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
        checkPublicKey(false, chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
        checkPublicKey(true, chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        validateAuthType(authType);
        validateChain(chain);
        checkPublicKey(false, chain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressFBWarnings(value = "WEAK_TRUST_MANAGER", justification = "An intentionally overtrusting manager.")
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
