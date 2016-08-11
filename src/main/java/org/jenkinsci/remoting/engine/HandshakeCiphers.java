/*
 * The MIT License
 *
 * Copyright (c) 2004-2015, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
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

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.spec.KeySpec;
import org.jenkinsci.remoting.util.Charsets;

/**
 * {@link Cipher}s that will be used to during the handshake
 * process for JNLP3 protocol.
 *
 * @author Akshay Dayal
 */
class HandshakeCiphers {

    private final SecretKey secretKey;
    private final IvParameterSpec spec;
    private final Cipher encryptCipher;
    private final Cipher decryptCipher;

    HandshakeCiphers(SecretKey secretKey, IvParameterSpec spec, Cipher encryptCipher,
            Cipher decryptCipher) {
        this.secretKey = secretKey;
        this.spec = spec;
        this.encryptCipher = encryptCipher;
        this.decryptCipher = decryptCipher;
    }

    /**
     * Encrypt a message that will be sent during the handshake process.
     *
     * @param raw The raw message to encrypt.
     * @throws IOException If there is an issue encrypting the message.
     */
    public String encrypt(String raw) throws IOException {
        try {
            String encrypted = new String(encryptCipher.doFinal(
                    raw.getBytes(Charsets.UTF_8)), Charsets.ISO_8859_1);
            encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            return encrypted;
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to encrypt message", e);
        }
    }

    /**
     * Decrypt a message that was received during the handshake process.
     *
     * @param encrypted The message to decrypt.
     * @throws IOException If there is an issue decrypting the message.
     */
    public String decrypt(String encrypted) throws IOException {
        try {
            String raw = new String(decryptCipher.doFinal(
                    encrypted.getBytes(Charsets.ISO_8859_1)), Charsets.UTF_8);
            decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            return raw;
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to decrypt message", e);
        }
    }

    /**
     * Create a pair of AES symmetric key {@link Cipher}s that
     * will be used during the handshake process.
     *
     * <p>The slave name and slave secret are used to create a
     * {@link PBEKeySpec} and an {@link IvParameterSpec}which is then used to
     * create the ciphers.
     *
     * @param salt The slave for which the handshake is taking place.
     * @param secret The slave secret.
     */
    public static HandshakeCiphers create(String salt, String secret) {
        try {
            byte[] specKey = Jnlp3Util.generate128BitKey(salt + secret);
            IvParameterSpec spec = new IvParameterSpec(specKey);

            SecretKey secretKey = generateSecretKey(salt, secret);
            Cipher encryptCipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            Cipher decryptCipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            return new HandshakeCiphers(secretKey, spec, encryptCipher, decryptCipher);
        } catch (GeneralSecurityException e) {
            throw (AssertionError)new AssertionError("Failed to create handshake ciphers").initCause(e);
        }
    }

    private static SecretKey generateSecretKey(String slaveName, String slaveSecret)
            throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(FACTORY_ALGORITHM);
        KeySpec spec = new PBEKeySpec(
                slaveSecret.toCharArray(), slaveName.getBytes(Charsets.UTF_8),
                INTEGRATION_COUNT, KEY_LENGTH);
        SecretKey tmpSecret = factory.generateSecret(spec);
        return new SecretKeySpec(tmpSecret.getEncoded(), SPEC_ALGORITHM);
    }

    private static final String CIPHER_TRANSFORMATION = "AES/CTR/PKCS5Padding";
    private static final String FACTORY_ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final String SPEC_ALGORITHM = "AES";
    private static final int INTEGRATION_COUNT = 65536;
    private static final int KEY_LENGTH = 128;
}
