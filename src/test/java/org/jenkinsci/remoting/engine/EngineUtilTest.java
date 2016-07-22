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

import org.jenkinsci.remoting.util.Charsets;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.util.Properties;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link EngineUtil}.
 *
 * @author Akshay Dayal
 */
public class EngineUtilTest {

    @Test
    public void testReadLine() throws Exception {
        assertEquals("first", EngineUtil.readLine(stringToStream("first\nsecond")));
        assertEquals("first-trim", EngineUtil.readLine(stringToStream("first-trim \nsecond")));
        assertEquals("onlyone", EngineUtil.readLine(stringToStream("onlyone")));
        assertEquals("onlyone-trim", EngineUtil.readLine(stringToStream("onlyone-trim ")));
        assertEquals("", EngineUtil.readLine(stringToStream("")));
    }

    @Test
    public void testReadChars() throws Exception {
        assertEquals("first", EngineUtil.readChars(stringToStream("firstsecond"), 5));
        assertEquals("", EngineUtil.readChars(stringToStream("firstsecond"), 0));
    }

    @Test
    public void testReadResponseHeadersNonePresent() throws Exception {
        assertEquals(new Properties(), EngineUtil.readResponseHeaders(stringToStream("")));
    }

    @Test
    public void testReadResponseHeadersSingleFound() throws Exception {
        Properties expected = new Properties();
        expected.put("a", "1");
        assertEquals(expected, EngineUtil.readResponseHeaders(stringToStream("a:1")));
        assertEquals(expected, EngineUtil.readResponseHeaders(stringToStream("a:1\n\nb:2")));
    }

    @Test
    public void testReadResponseHeadersMultipleFound() throws Exception {
        Properties expected = new Properties();
        expected.put("a", "1");
        expected.put("b", "2");
        assertEquals(expected, EngineUtil.readResponseHeaders(stringToStream("a:1\nb:2")));
        assertEquals(expected, EngineUtil.readResponseHeaders(stringToStream("a:1\nb:2\n\nc:3")));
    }

    private BufferedInputStream stringToStream(String str) {
        return new BufferedInputStream(new ByteArrayInputStream(str.getBytes(Charsets.UTF_8)));
    }
}
