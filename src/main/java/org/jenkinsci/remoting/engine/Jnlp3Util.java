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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;
import org.jenkinsci.remoting.util.Charsets;

/**
 * Utility methods for JNLP3.
 *
 * @author Akshay Dayal
 */
class Jnlp3Util {

    /**
     * Generate a random 128bit key.
     */
    public static byte[] generate128BitKey(Random entropy) {
        byte[] key = new byte[16];
        entropy.nextBytes(key);
        return key;
    }

    /**
     * Generate a 128bit key from the given string.
     */
    public static byte[] generate128BitKey(String str) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(str.getBytes(Charsets.UTF_8));
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
        return new String(key, Charsets.ISO_8859_1);
    }

    /**
     * Get back the original key from the given string.
     */
    public static byte[] keyFromString(String keyString) {
        return keyString.getBytes(Charsets.ISO_8859_1);
    }

    /**
     * Generate a random challenge phrase.
     * @param entropy
     */
    public static String generateChallenge(Random entropy) {
        return new BigInteger(10400, entropy).toString(32);
    }

    /**
     * Create a response to a given challenge.
     *
     * <p>The response is a SHA-256 hash of the challenge string (probably mangled by UTF-8 encoding issues).
     */
    public static String createChallengeResponse(String challenge) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(challenge.getBytes(Charsets.UTF_8));
            return new String(messageDigest.digest(), Charsets.UTF_8); // <--- One of the root causes of JENKINS-37315
        } catch (NoSuchAlgorithmException nsae) {
            // This should never happen.
            throw new AssertionError(nsae);
        }
    }

    /**
     * Validate the given challenge response matches for the given challenge.
     */
    public static boolean validateChallengeResponse(String challenge, String challengeResponse) {
        String expectedResponse = createChallengeResponse(challenge);
        if (expectedResponse.equals(challengeResponse)) {
            return true;
        }
        // JENKINS-37315 fallback to comparing the encoded bytes because the format should never have used UTF-8
        if (Arrays.equals(expectedResponse.getBytes(Charsets.UTF_8), challengeResponse.getBytes(Charsets.UTF_8))) {
            return true;
        }
        return false;
    }
}
