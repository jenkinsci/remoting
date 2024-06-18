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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigInteger;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class X509CertificateRule implements TestRule {
    private static final BouncyCastleProvider BOUNCY_CASTLE_PROVIDER = new BouncyCastleProvider();
    private final KeyPairRule<? extends PublicKey, ? extends PrivateKey> subjectKey;
    private final KeyPairRule<? extends PublicKey, ? extends PrivateKey> signerKey;
    private X509CertificateRule signerCertificate;
    private final long startDateOffsetMillis;
    private final long endDateOffsetMillis;
    private final String id;
    private X509Certificate certificate;

    public static <PUB extends PublicKey, PRIV extends PrivateKey> X509CertificateRule selfSigned(
            String id, KeyPairRule<PUB, PRIV> subject) {
        return new X509CertificateRule(id, subject, subject, null, -7, 7, TimeUnit.DAYS);
    }

    public static <PUB extends PublicKey, PRIV extends PrivateKey> X509CertificateRule create(
            String id,
            KeyPairRule<PUB, PRIV> subject,
            KeyPairRule<PUB, PRIV> signer,
            X509CertificateRule signerCertificate) {
        return new X509CertificateRule(id, subject, signer, signerCertificate, -7, 7, TimeUnit.DAYS);
    }

    public static <PUB extends PublicKey, PRIV extends PrivateKey> X509CertificateRule selfSigned(
            KeyPairRule<PUB, PRIV> subject) {
        return selfSigned("", subject);
    }

    public static <PUB extends PublicKey, PRIV extends PrivateKey> X509CertificateRule create(
            KeyPairRule<PUB, PRIV> subject, KeyPairRule<PUB, PRIV> signer, X509CertificateRule signerCertificate) {
        return create("", subject, signer, signerCertificate);
    }

    public static <PUB extends PublicKey, PRIV extends PrivateKey> X509CertificateRule create(
            String id,
            KeyPairRule<PUB, PRIV> subject,
            KeyPairRule<PUB, PRIV> signer,
            X509CertificateRule signerCertificate,
            long startDateOffset,
            long endDateOffset,
            TimeUnit units) {
        return new X509CertificateRule(id, subject, signer, signerCertificate, startDateOffset, endDateOffset, units);
    }

    public X509CertificateRule(
            String id,
            KeyPairRule<? extends PublicKey, ? extends PrivateKey> subjectKey,
            KeyPairRule<? extends PublicKey, ? extends PrivateKey> signerKey,
            X509CertificateRule signerCertificate,
            long startDateOffset,
            long endDateOffset,
            TimeUnit units) {
        this.id = id;
        this.subjectKey = subjectKey;
        this.signerKey = signerKey;
        this.signerCertificate = signerCertificate;
        this.startDateOffsetMillis = units.toMillis(startDateOffset);
        this.endDateOffsetMillis = units.toMillis(endDateOffset);
    }

    public X509Certificate certificate() {
        return certificate;
    }

    @Override
    public Statement apply(final Statement base, final Description description) {
        Skip skip = description.getAnnotation(Skip.class);
        if (skip != null
                && (skip.value().length == 0 || Arrays.asList(skip.value()).contains(id))) {
            return base;
        }
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Date now = new Date();
                Date firstDate = new Date(now.getTime() + startDateOffsetMillis);
                Date lastDate = new Date(now.getTime() + endDateOffsetMillis);

                X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
                if (id != null) {
                    nameBuilder.addRDN(BCStyle.CN, id);
                }
                X500Principal subject = new X500Principal(nameBuilder
                        .addRDN(BCStyle.CN, description.getDisplayName())
                        .addRDN(BCStyle.C, "US")
                        .build()
                        .toString());

                X500Principal issuer = signerCertificate != null
                        ? signerCertificate.certificate().getSubjectX500Principal()
                        : subject;

                X509v3CertificateBuilder certGen = new JcaX509v3CertificateBuilder(
                        issuer, BigInteger.ONE, firstDate, lastDate, subject, subjectKey.getPublic());

                JcaX509ExtensionUtils instance = new JcaX509ExtensionUtils();

                certGen.addExtension(
                        Extension.subjectKeyIdentifier,
                        false,
                        instance.createSubjectKeyIdentifier(subjectKey.getPublic()));

                ContentSigner signer = new JcaContentSignerBuilder("SHA1withRSA")
                        .setProvider(BOUNCY_CASTLE_PROVIDER)
                        .build(X509CertificateRule.this.signerKey.getPrivate());

                certificate = new JcaX509CertificateConverter()
                        .setProvider(BOUNCY_CASTLE_PROVIDER)
                        .getCertificate(certGen.build(signer));
                try {
                    base.evaluate();
                } finally {
                    certificate = null;
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
}
