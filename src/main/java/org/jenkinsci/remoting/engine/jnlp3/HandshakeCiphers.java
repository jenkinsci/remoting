package org.jenkinsci.remoting.engine.jnlp3;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.spec.KeySpec;

/**
 * {@link javax.crypto.Cipher}s that will be used to during the handshake
 * process for JNLP3 protocol.
 *
 * @author Akshay Dayal
 */
public class HandshakeCiphers {

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

    public byte[] getSpecKey() {
        return spec.getIV();
    }

    /**
     * Encrypt a message that will be sent during the handshake process.
     *
     * @param raw The raw message to encrypt.
     * @throws Exception If there is an issue encrypting the message.
     */
    public String encrypt(String raw) throws Exception {
        String encrypted = new String(encryptCipher.doFinal(raw.getBytes("UTF-8")), "ISO-8859-1");
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
        return encrypted;
    }

    /**
     * Decrypt a message that was received during the handshake process.
     *
     * @param encrypted The message to decrypt.
     * @throws Exception If there is an issue decrypting the message.
     */
    public String decrypt(String encrypted) throws Exception {
        String raw = new String(decryptCipher.doFinal(encrypted.getBytes("ISO-8859-1")), "UTF-8");
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        return raw;
    }

    /**
     * Create a pair of AES symmetric key {@link javax.crypto.Cipher}s that
     * will be used during the handshake process.
     *
     * <p>The slave name and slave secret are used to create a
     * {@link PBEKeySpec} which is then used to create the ciphers. If a
     * specKey is not provided one will be generated.
     *
     * <p>The person initiating the handshake would let a specKey be generated
     * and send that to the other participant so they will be able to create
     * identical ciphers.
     *
     * @param slaveName The slave for which the handshake is taking place.
     * @param slaveSecret The slave secret.
     * @param specKey The spec key to use.
     * @throws Exception If there is a problem creating the ciphers.
     */
    public static HandshakeCiphers create(
            String slaveName, String slaveSecret, @Nullable byte[] specKey) throws Exception {
        if (specKey == null) {
            specKey = CipherUtils.generate128BitKey();
        }

        SecretKey secretKey = generateSecretKey(slaveName, slaveSecret);
        IvParameterSpec spec = new IvParameterSpec(specKey);
        Cipher encryptCipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
        Cipher decryptCipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        return new HandshakeCiphers(secretKey, spec, encryptCipher, decryptCipher);
    }

    private static SecretKey generateSecretKey(String slaveName, String slaveSecret)
            throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(FACTORY_ALGORITHM);
        KeySpec spec = new PBEKeySpec(
                slaveSecret.toCharArray(), slaveName.getBytes("UTF-8"),
                INTEGRATION_COUNT, KEY_LENGTH);
        SecretKey tmpSecret = factory.generateSecret(spec);
        return new SecretKeySpec(tmpSecret.getEncoded(), SPEC_ALGORITHM);
    }

    static final String CIPHER_TRANSFORMATION = "AES/CTR/PKCS5Padding";
    static final String FACTORY_ALGORITHM = "PBKDF2WithHmacSHA1";
    static final String SPEC_ALGORITHM = "AES";
    static final int INTEGRATION_COUNT = 65536;
    static final int KEY_LENGTH = 128;
}
