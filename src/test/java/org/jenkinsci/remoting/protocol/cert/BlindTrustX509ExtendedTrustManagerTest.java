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
package org.jenkinsci.remoting.protocol.cert;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class BlindTrustX509ExtendedTrustManagerTest {

    private final BlindTrustX509ExtendedTrustManager instance = new BlindTrustX509ExtendedTrustManager();

    @RegisterExtension
    private static final RSAKeyPairExtension KEY = new RSAKeyPairExtension("main");

    @RegisterExtension
    private static final X509CertificateExtension CERT =
            new X509CertificateExtension("main", KEY, KEY, null, -1, 1, TimeUnit.HOURS);

    @Test
    void checkClientTrusted() {
        instance.checkClientTrusted(new X509Certificate[] {CERT.certificate()}, "RSA");
    }

    @Test
    void checkClientTrusted_nullNonNull() {
        assertThrows(IllegalArgumentException.class, () -> instance.checkClientTrusted(null, "RSA"));
    }

    @Test
    void checkClientTrusted_nonNullNull() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkClientTrusted(new X509Certificate[] {CERT.certificate()}, null));
    }

    @Test
    void checkClientTrusted_nonNullEmpty() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkClientTrusted(new X509Certificate[] {CERT.certificate()}, ""));
    }

    @Test
    void checkServerTrusted() {
        instance.checkServerTrusted(new X509Certificate[] {CERT.certificate()}, "RSA");
    }

    @Test
    void checkServerTrusted_nullNonNull() {
        assertThrows(IllegalArgumentException.class, () -> instance.checkServerTrusted(null, "RSA"));
    }

    @Test
    void checkServerTrusted_nonNullNull() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkServerTrusted(new X509Certificate[] {CERT.certificate()}, null));
    }

    @Test
    void checkServerTrusted_nonNullEmpty() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkServerTrusted(new X509Certificate[] {CERT.certificate()}, ""));
    }

    @Test
    void checkClientTrusted1() {
        instance.checkClientTrusted(new X509Certificate[] {CERT.certificate()}, "RSA", new Socket());
    }

    @Test
    void checkClientTrusted1_nullNonNull() {
        assertThrows(IllegalArgumentException.class, () -> instance.checkClientTrusted(null, "RSA", new Socket()));
    }

    @Test
    void checkClientTrusted1_nonNullNull() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkClientTrusted(new X509Certificate[] {CERT.certificate()}, null, new Socket()));
    }

    @Test
    void checkClientTrusted1_nonNullEmpty() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkClientTrusted(new X509Certificate[] {CERT.certificate()}, "", new Socket()));
    }

    @Test
    void checkServerTrusted1() {
        instance.checkServerTrusted(new X509Certificate[] {CERT.certificate()}, "RSA", new Socket());
    }

    @Test
    void checkServerTrusted1_nullNonNull() {
        assertThrows(IllegalArgumentException.class, () -> instance.checkServerTrusted(null, "RSA", new Socket()));
    }

    @Test
    void checkServerTrusted1_nonNullNull() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkServerTrusted(new X509Certificate[] {CERT.certificate()}, null, new Socket()));
    }

    @Test
    void checkServerTrusted1_nonNullEmpty() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkServerTrusted(new X509Certificate[] {CERT.certificate()}, "", new Socket()));
    }

    @Test
    void checkClientTrusted2() {
        instance.checkClientTrusted(new X509Certificate[] {CERT.certificate()}, "RSA", (SSLEngine) null);
    }

    @Test
    void checkClientTrusted2_nullNonNull() {
        assertThrows(IllegalArgumentException.class, () -> instance.checkClientTrusted(null, "RSA", (SSLEngine) null));
    }

    @Test
    void checkClientTrusted2_nonNullNull() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkClientTrusted(new X509Certificate[] {CERT.certificate()}, null, (SSLEngine) null));
    }

    @Test
    void checkClientTrusted2_nonNullEmpty() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkClientTrusted(new X509Certificate[] {CERT.certificate()}, "", (SSLEngine) null));
    }

    @Test
    void checkServerTrusted2() {
        instance.checkServerTrusted(new X509Certificate[] {CERT.certificate()}, "RSA", (SSLEngine) null);
    }

    @Test
    void checkServerTrusted2_nullNonNull() {
        assertThrows(IllegalArgumentException.class, () -> instance.checkServerTrusted(null, "RSA", (SSLEngine) null));
    }

    @Test
    void checkServerTrusted2_nonNullNull() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkServerTrusted(new X509Certificate[] {CERT.certificate()}, null, (SSLEngine) null));
    }

    @Test
    void checkServerTrusted2_nonNullEmpty() {
        assertThrows(
                IllegalArgumentException.class,
                () -> instance.checkServerTrusted(new X509Certificate[] {CERT.certificate()}, "", (SSLEngine) null));
    }

    @Test
    void getAcceptedIssuers() {
        assertThat(instance.getAcceptedIssuers(), notNullValue());
    }
}
