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

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Random;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link javax.crypto.Cipher}s that will be used to construct an encrypted
 * {@link hudson.remoting.Channel} after a successful handshake.
 *
 * @author Akshay Dayal
 */
class ChannelCiphers {

    private final byte[] aesKey;
    private final byte[] specKey;
    private final Cipher encryptCipher;
    private final Cipher decryptCipher;

    ChannelCiphers(byte[] aesKey, byte[] specKey, Cipher encryptCipher, Cipher decryptCipher) {
        this.aesKey = aesKey;
        this.specKey = specKey;
        this.encryptCipher = encryptCipher;
        this.decryptCipher = decryptCipher;
    }

    public byte[] getAesKey() {
        return aesKey;
    }

    public byte[] getSpecKey() {
        return specKey;
    }

    public Cipher getEncryptCipher() {
        return encryptCipher;
    }

    public Cipher getDecryptCipher() {
        return decryptCipher;
    }

    /**
     * Create a pair of AES symmetric key {@link javax.crypto.Cipher}s using
     * randomly generated keys.
     *
     * @throws IOException If there is a problem constructing the ciphers.
     */
    public static ChannelCiphers create(Random entropy) throws IOException {
        return create(Jnlp3Util.generate128BitKey(entropy), Jnlp3Util.generate128BitKey(entropy));
    }

    /**
     * Creates a pair of AES symmetric key {@link javax.crypto.Cipher}s using
     * the given AES key and {@link javax.crypto.spec.IvParameterSpec} key.
     *
     * @throws IOException If there is a problem constructing the ciphers.
     */
    public static ChannelCiphers create(byte[] aesKey, byte[] specKey) throws IOException {
        try {
            SecretKey secretKey = new SecretKeySpec(aesKey, "AES");
            IvParameterSpec spec = new IvParameterSpec(specKey);
            Cipher encryptCipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            Cipher decryptCipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            return new ChannelCiphers(aesKey, specKey, encryptCipher, decryptCipher);
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to create channel ciphers", e);
        }
    }

    private static final String CIPHER_TRANSFORMATION = "AES/CTR/PKCS5Padding";
}
