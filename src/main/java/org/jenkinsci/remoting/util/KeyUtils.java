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
package org.jenkinsci.remoting.util;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Utility methods to help working with {@link Key} instances.
 */
public final class KeyUtils {

    /**
     * Utility class.
     */
    private KeyUtils() {
        throw new IllegalAccessError("Utility class");
    }

    /**
     * Check two keys for equality.
     *
     * @param key1 the first key.
     * @param key2 the second key.
     * @return {@code true} if we can confirm that the two keys are identical, {@code false} otherwise.
     */
    public static boolean equals(Key key1, Key key2) {
        if (key1 == key2) {
            return true;
        }
        if (key1 == null || key2 == null) {
            return false;
        }
        if (!equals(key1.getAlgorithm(), key1.getAlgorithm())) {
            return false;
        }
        if (!equals(key1.getFormat(), key1.getFormat())) {
            // expecting these to pretty much always be "X.509" for PublicKeys or "PKCS#8" for PrivateKeys
            return false;
        }
        byte[] encoded1 = key1.getEncoded();
        byte[] encoded2 = key2.getEncoded();
        if (encoded1 == null || encoded2 == null) {
            // If only one does not support encoding, then they are different.
            // If both do not support encoding, while they may be the same, we have no way of knowing.
            return false;
        }
        return MessageDigest.isEqual(key1.getEncoded(), key2.getEncoded());
    }

    /**
     * A helper method for comparing two strings (we'd use StringUtils.equals only that adds more dependencies and
     * remoting has a generic need to be more self contained.
     *
     * @param str1 the first string.
     * @param str2 the second string.
     * @return {@code true} if the two strings are equal.
     */
    private static boolean equals(String str1, String str2) {
        return Objects.equals(str1, str2);
    }

    /**
     * Returns the MD5 fingerprint of a key formatted in the normal way for key fingerprints
     *
     * @param key the key.
     * @return the MD5 fingerprint of the key.
     */
    @NonNull
    @SuppressFBWarnings(value = "WEAK_MESSAGE_DIGEST_MD5", justification = "Used for fingerprinting, not security.")
    public static String fingerprint(@CheckForNull Key key) {
        if (key == null) {
            return "null";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.reset();
            byte[] bytes = digest.digest(key.getEncoded());
            StringBuilder result = new StringBuilder(Math.max(0, bytes.length * 3 - 1));
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) {
                    result.append(':');
                }
                result.append(Character.forDigit((bytes[i] >> 4) & 0x0f, 16));
                result.append(Character.forDigit(bytes[i] & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JLS mandates MD5 support");
        }
    }
}
