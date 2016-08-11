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
import org.jenkinsci.remoting.util.Charsets;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link ChannelCiphers}.
 *
 * @author Akshay Dayal
 */
public class ChannelCiphersTest {

    private static final Random ENTROPY = new SecureRandom();

    @Test
    public void testEncryptDecrypt() throws Exception {
        ChannelCiphers ciphers = ChannelCiphers.create(ENTROPY);

        byte[] encrypted = ciphers.getEncryptCipher().doFinal(
                "string 1".getBytes(Charsets.UTF_8));
        String decrypted = new String(
                ciphers.getDecryptCipher().doFinal(encrypted), Charsets.UTF_8);
        assertEquals("string 1", decrypted);
    }

    @Test
    public void testMatchingWithKeys() throws Exception {
        ChannelCiphers ciphers1 = ChannelCiphers.create(ENTROPY);
        ChannelCiphers ciphers2 = ChannelCiphers.create(
                ciphers1.getAesKey(), ciphers1.getSpecKey());

        byte[] encrypted = ciphers1.getEncryptCipher().doFinal(
                "string 1".getBytes(Charsets.UTF_8));
        String decrypted = new String(
                ciphers2.getDecryptCipher().doFinal(encrypted), Charsets.UTF_8);
        assertEquals("string 1", decrypted);
        assertEquals(16, ciphers1.getAesKey().length);
        assertEquals(16, ciphers1.getSpecKey().length);
    }
}
