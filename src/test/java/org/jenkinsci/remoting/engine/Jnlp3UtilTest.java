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

import java.security.SecureRandom;
import java.util.Random;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.jenkinsci.remoting.util.Charsets;
import org.junit.Test;

import java.security.MessageDigest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link Jnlp3Util}.
 *
 * @author Akshay Dayal
 */
public class Jnlp3UtilTest {

    private static final Random ENTROPY = new SecureRandom();

    @Test
    public void testGenerate128BitKey() {
        byte[] firstKey = Jnlp3Util.generate128BitKey(ENTROPY);
        assertEquals(16, firstKey.length);
        byte[] secondKey = Jnlp3Util.generate128BitKey(ENTROPY);
        assertEquals(16, secondKey.length);
        assertThat(firstKey, IsNot.not(IsEqual.equalTo(secondKey)));
    }

    @Test
    public void testGenerate128BitKeyFromString() throws Exception {
        byte[] firstKey = Jnlp3Util.generate128BitKey("This is a string");
        byte[] secondKey = Jnlp3Util.generate128BitKey("This is a string too");
        byte[] thirdKey = Jnlp3Util.generate128BitKey("This is a string");
        assertEquals(16, firstKey.length);
        assertEquals(16, secondKey.length);
        assertArrayEquals(firstKey, thirdKey);
    }

    @Test
    public void testKeyToString() throws Exception {
        byte[] key = "This is a string".getBytes(Charsets.UTF_8);
        assertEquals(new String(key, Charsets.ISO_8859_1), Jnlp3Util.keyToString(key));
    }

    @Test
    public void testKeyFromString() throws Exception {
        byte[] key = "This is a string".getBytes(Charsets.UTF_8);
        assertArrayEquals(key,
                Jnlp3Util.keyFromString(new String(key, Charsets.ISO_8859_1)));
    }

    @Test
    public void testGenerateChallenge() {
        String challenge1 = Jnlp3Util.generateChallenge(ENTROPY);
        String challenge2 = Jnlp3Util.generateChallenge(ENTROPY);
        assertNotEquals(challenge1, challenge2);
    }

    @Test
    public void testCreateChallengeResponse() throws Exception {
        String challenge = "This is a challenge string";
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        messageDigest.update(challenge.getBytes(Charsets.UTF_8));
        String expectedResponse = new String(messageDigest.digest(), Charsets.UTF_8);
        assertEquals(expectedResponse, Jnlp3Util.createChallengeResponse(challenge));
    }

    @Test
    public void testValidateChallengeResponse() throws Exception {
        String challenge = "This is a challenge string";
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        messageDigest.update(challenge.getBytes(Charsets.UTF_8));
        String challengeResponse = new String(messageDigest.digest(), Charsets.UTF_8);

        assertTrue(Jnlp3Util.validateChallengeResponse(challenge, challengeResponse));
        assertFalse(Jnlp3Util.validateChallengeResponse(challenge, "some string"));
    }
}
