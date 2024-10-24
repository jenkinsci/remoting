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

import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.RuleChain;

public class BlindTrustX509ExtendedTrustManagerTest {

    private BlindTrustX509ExtendedTrustManager instance = new BlindTrustX509ExtendedTrustManager();

    public static RSAKeyPairRule key = new RSAKeyPairRule("main");

    public static X509CertificateRule cert = new X509CertificateRule("main", key, key, null, -1, 1, TimeUnit.HOURS);

    @ClassRule
    public static RuleChain chain = RuleChain.outerRule(key).around(cert);

    @Test
    public void checkClientTrusted() throws Exception {
        instance.checkClientTrusted(new X509Certificate[] {cert.certificate()}, "RSA");
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkClientTrusted_nullNonNull() throws Exception {
        instance.checkClientTrusted(null, "RSA");
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkClientTrusted_nonNullNull() throws Exception {
        instance.checkClientTrusted(new X509Certificate[] {cert.certificate()}, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkClientTrusted_nonNullEmpty() throws Exception {
        instance.checkClientTrusted(new X509Certificate[] {cert.certificate()}, "");
    }

    @Test
    public void checkServerTrusted() throws Exception {
        instance.checkServerTrusted(new X509Certificate[] {cert.certificate()}, "RSA");
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkServerTrusted_nullNonNull() throws Exception {
        instance.checkServerTrusted(null, "RSA");
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkServerTrusted_nonNullNull() throws Exception {
        instance.checkServerTrusted(new X509Certificate[] {cert.certificate()}, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkServerTrusted_nonNullEmpty() throws Exception {
        instance.checkServerTrusted(new X509Certificate[] {cert.certificate()}, "");
    }

    @Test
    public void checkClientTrusted1() throws Exception {
        instance.checkClientTrusted(new X509Certificate[] {cert.certificate()}, "RSA", new Socket());
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkClientTrusted1_nullNonNull() throws Exception {
        instance.checkClientTrusted(null, "RSA", new Socket());
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkClientTrusted1_nonNullNull() throws Exception {
        instance.checkClientTrusted(new X509Certificate[] {cert.certificate()}, null, new Socket());
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkClientTrusted1_nonNullEmpty() throws Exception {
        instance.checkClientTrusted(new X509Certificate[] {cert.certificate()}, "", new Socket());
    }

    @Test
    public void checkServerTrusted1() throws Exception {
        instance.checkServerTrusted(new X509Certificate[] {cert.certificate()}, "RSA", new Socket());
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkServerTrusted1_nullNonNull() throws Exception {
        instance.checkServerTrusted(null, "RSA", new Socket());
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkServerTrusted1_nonNullNull() throws Exception {
        instance.checkServerTrusted(new X509Certificate[] {cert.certificate()}, null, new Socket());
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkServerTrusted1_nonNullEmpty() throws Exception {
        instance.checkServerTrusted(new X509Certificate[] {cert.certificate()}, "", new Socket());
    }

    @Test
    public void checkClientTrusted2() throws Exception {
        instance.checkClientTrusted(new X509Certificate[] {cert.certificate()}, "RSA", (SSLEngine) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkClientTrusted2_nullNonNull() throws Exception {
        instance.checkClientTrusted(null, "RSA", (SSLEngine) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkClientTrusted2_nonNullNull() throws Exception {
        instance.checkClientTrusted(new X509Certificate[] {cert.certificate()}, null, (SSLEngine) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkClientTrusted2_nonNullEmpty() throws Exception {
        instance.checkClientTrusted(new X509Certificate[] {cert.certificate()}, "", (SSLEngine) null);
    }

    @Test
    public void checkServerTrusted2() throws Exception {
        instance.checkServerTrusted(new X509Certificate[] {cert.certificate()}, "RSA", (SSLEngine) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkServerTrusted2_nullNonNull() throws Exception {
        instance.checkServerTrusted(null, "RSA", (SSLEngine) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkServerTrusted2_nonNullNull() throws Exception {
        instance.checkServerTrusted(new X509Certificate[] {cert.certificate()}, null, (SSLEngine) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void checkServerTrusted2_nonNullEmpty() throws Exception {
        instance.checkServerTrusted(new X509Certificate[] {cert.certificate()}, "", (SSLEngine) null);
    }

    @Test
    public void getAcceptedIssuers() {
        assertThat(instance.getAcceptedIssuers(), notNullValue());
    }
}
