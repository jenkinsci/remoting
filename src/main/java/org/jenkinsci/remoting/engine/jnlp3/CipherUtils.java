package org.jenkinsci.remoting.engine.jnlp3;

import java.io.UnsupportedEncodingException;
import java.security.SecureRandom;

/**
 * Cipher related utility methods for JNLP3.
 *
 * @author Akshay Dayal
 */
public class CipherUtils {

    /**
     * Generate a random 128bit key.
     */
    public static byte[] generate128BitKey() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /**
     * Convert the given key to a string.
     */
    public static String keyToString(byte[] key) throws UnsupportedEncodingException {
        return new String(key, "ISO-8859-1");
    }

    /**
     * Get back the original key from the given string.
     */
    public static byte[] keyFromString(String keyString) throws UnsupportedEncodingException {
        return keyString.getBytes("ISO-8859-1");
    }
}
