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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Tests for {@link HandshakeCiphers}.
 *
 * @author Akshay Dayal
 */
public class HandshakeCiphersTest {

    @Test
    public void testEncryptDecrypt() throws Exception {
        HandshakeCiphers ciphers = HandshakeCiphers.create("some slave", "some secret");

        assertNotEquals("string 1", ciphers.encrypt("string 1"));
        assertEquals("string 1", ciphers.decrypt(ciphers.encrypt("string 1")));
        assertNotEquals("string 2", ciphers.encrypt("string 2"));
        assertEquals("string 2", ciphers.decrypt(ciphers.encrypt("string 2")));
    }

    @Test
    public void testMatchingWithSameValues() throws Exception {
        HandshakeCiphers ciphers1 = HandshakeCiphers.create("some slave", "some secret");
        HandshakeCiphers ciphers2 = HandshakeCiphers.create("some slave", "some secret");

        assertEquals("string 1", ciphers2.decrypt(ciphers1.encrypt("string 1")));
        assertEquals("string 2", ciphers1.decrypt(ciphers2.encrypt("string 2")));
    }

    @Test
    public void testNotMatchingWithDifferentValues() throws Exception {
        HandshakeCiphers ciphers1 = HandshakeCiphers.create("some slave", "some secret");
        HandshakeCiphers ciphers2 = HandshakeCiphers.create("other slave", "other secret");

        assertNotEquals("string 1", ciphers2.decrypt(ciphers1.encrypt("string 1")));
        assertNotEquals("string 2", ciphers1.decrypt(ciphers2.encrypt("string 2")));
    }
}
