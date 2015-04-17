package org.jenkinsci.remoting.engine.jnlp3;

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
public class ChannelCiphers {

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
     * @throws Exception If there is a problem constructing the ciphers.
     */
    public static ChannelCiphers create() throws Exception {
        return create(CipherUtils.generate128BitKey(), CipherUtils.generate128BitKey());
    }

    /**
     * Creates a pair of AES symmetric key {@link javax.crypto.Cipher}s using
     * the given AES key and {@link javax.crypto.spec.IvParameterSpec} key.
     *
     * @throws Exception If there is a problem constructing the ciphers.
     */
    public static ChannelCiphers create(byte[] aesKey, byte[] specKey) throws Exception {
        SecretKey secretKey = new SecretKeySpec(aesKey, "AES");
        IvParameterSpec spec = new IvParameterSpec(specKey);
        Cipher encryptCipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
        Cipher decryptCipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        return new ChannelCiphers(aesKey, specKey, encryptCipher, decryptCipher);
    }

    static final String CIPHER_TRANSFORMATION = "AES/CTR/PKCS5Padding";
}
