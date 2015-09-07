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

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Utility methods for JNLP3.
 *
 * @author Akshay Dayal
 */
class Jnlp3Util {

    /**
     * Generate a random 128bit key.
     */
    public static byte[] generate128BitKey() {
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /**
     * Generate a 128bit key from the given string.
     */
    public static byte[] generate128BitKey(String str) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(str.getBytes(Charset.forName("UTF-8")));
            return Arrays.copyOf(messageDigest.digest(), 16);
        } catch (NoSuchAlgorithmException nsae) {
            // This should never happen.
            throw new AssertionError(nsae);
        }
    }

    /**
     * Convert the given key to a string.
     */
    public static String keyToString(byte[] key) {
        return new String(key, Charset.forName("ISO-8859-1"));
    }

    /**
     * Get back the original key from the given string.
     */
    public static byte[] keyFromString(String keyString) {
        return keyString.getBytes(Charset.forName("ISO-8859-1"));
    }

    /**
     * Generate a random challenge phrase.
     */
    public static String generateChallenge() {
        return new BigInteger(10400, new SecureRandom()).toString(32);
    }

    /**
     * Create a response to a given challenge.
     *
     * <p>The response is a SHA-256 hash of the challenge string.
     */
    public static String createChallengeResponse(String challenge) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(challenge.getBytes(Charset.forName("UTF-8")));
            return new String(messageDigest.digest(), Charset.forName("UTF-8"));
        } catch (NoSuchAlgorithmException nsae) {
            // This should never happen.
            throw new AssertionError(nsae);
        }
    }

    /**
     * Validate the given challenge response matches for the given challenge.
     */
    public static boolean validateChallengeResponse(String challenge, String challengeResponse) {
        return challengeResponse.equals(createChallengeResponse(challenge));
    }
}
