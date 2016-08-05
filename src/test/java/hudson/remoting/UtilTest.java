/*
 * The MIT License
 *
 * Copyright (c) 2016, Schneider Electric
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

package hudson.remoting;

import junit.framework.TestCase;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.io.IOException;

/**
 * @author Etienne Bec
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(Util.class)
public class UtilTest extends TestCase {

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Before
    public void mockSystem() {
        PowerMockito.mockStatic(System.class);
    }

    @Test
    public void testIPV4() {
        PowerMockito.when(System.getenv("no_proxy")).thenReturn("10.0.0.1");

        assertEquals(true, Util.inNoProxyEnvVar("10.0.0.1"));
    }

    @Test
    public void testWrongIPV4() {
        PowerMockito.when(System.getenv("no_proxy")).thenReturn("127.0.0.1");

        assertEquals(false, Util.inNoProxyEnvVar("10.0.0.1"));
    }

    @Test
    public void testIPV6() {
        PowerMockito.when(System.getenv("no_proxy")).thenReturn("2001:0db8:85a3:0000:0000:8a2e:0370:7334");
        assertEquals(true, Util.inNoProxyEnvVar("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
    }

    @Test
    public void testWrongIPV6() {
        PowerMockito.when(System.getenv("no_proxy")).thenReturn("0:0:0:0:0:0:0:1");

        assertEquals(false, Util.inNoProxyEnvVar("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
    }

    @Test
    public void testFQDN() {
        PowerMockito.when(System.getenv("no_proxy")).thenReturn("foobar.com");

        assertEquals(true, Util.inNoProxyEnvVar("foobar.com"));
        assertEquals(true, Util.inNoProxyEnvVar("sub.foobar.com"));
        assertEquals(true, Util.inNoProxyEnvVar("sub.sub.foobar.com"));

        assertEquals(false, Util.inNoProxyEnvVar("foobar.org"));
        assertEquals(false, Util.inNoProxyEnvVar("jenkins.com"));
    }

    @Test
    public void testSubFQDN() {
        PowerMockito.when(System.getenv("no_proxy")).thenReturn("sub.foobar.com");

        assertEquals(true, Util.inNoProxyEnvVar("sub.foobar.com"));
        assertEquals(true, Util.inNoProxyEnvVar("sub.sub.foobar.com"));

        assertEquals(false, Util.inNoProxyEnvVar("foobar.com"));
    }

    @Test
    public void testFQDNWithDot() {
        PowerMockito.when(System.getenv("no_proxy")).thenReturn(".foobar.com");

        assertEquals(true, Util.inNoProxyEnvVar("foobar.com"));
        assertEquals(true, Util.inNoProxyEnvVar("sub.foobar.com"));
        assertEquals(true, Util.inNoProxyEnvVar("sub.sub.foobar.com"));

        assertEquals(false, Util.inNoProxyEnvVar("foobar.org"));
        assertEquals(false, Util.inNoProxyEnvVar("jenkins.com"));
    }

    @Test
    public void testSubFQDNWithDot() {
        PowerMockito.when(System.getenv("no_proxy")).thenReturn(".sub.foobar.com");

        assertEquals(true, Util.inNoProxyEnvVar("sub.foobar.com"));
        assertEquals(true, Util.inNoProxyEnvVar("sub.sub.foobar.com"));

        assertEquals(false, Util.inNoProxyEnvVar("foobar.com"));
    }

    @Test
    public void testMixed() {
        PowerMockito.when(System.getenv("no_proxy")).thenReturn(" 127.0.0.1,  0:0:0:0:0:0:0:1,\tfoobar.com, .jenkins.com");

        assertEquals(true, Util.inNoProxyEnvVar("127.0.0.1"));
        assertEquals(true, Util.inNoProxyEnvVar("0:0:0:0:0:0:0:1"));
        assertEquals(true, Util.inNoProxyEnvVar("foobar.com"));
        assertEquals(true, Util.inNoProxyEnvVar("sub.foobar.com"));
        assertEquals(true, Util.inNoProxyEnvVar("sub.jenkins.com"));

        assertEquals(false, Util.inNoProxyEnvVar("foobar.org"));
        assertEquals(false, Util.inNoProxyEnvVar("jenkins.org"));
        assertEquals(false, Util.inNoProxyEnvVar("sub.foobar.org"));
        assertEquals(false, Util.inNoProxyEnvVar("sub.jenkins.org"));
    }

    @Test
    public void mkdirs() throws IOException {
        File sandbox = temp.newFolder();
        // Dir exists already
        Util.mkdirs(sandbox);
        assertTrue(sandbox.exists());

        // Create nested subdir
        File subdir = new File(sandbox, "sub/dir");
        Util.mkdirs(subdir);
        assertTrue(subdir.exists());

        // Do not overwrite a file
        File file = new File(sandbox, "regular.file");
        file.createNewFile();
        try {
            Util.mkdirs(file);
        } catch (IOException ex) {
            // Expected
        }
        assertTrue(file.exists());
        assertTrue(file.isFile());

        // Fail to create aloud
        try {
            File forbidden = new File("/proc/nonono");
            Util.mkdirs(forbidden);
            fail();
        } catch (IOException ex) {
            // Expected
        }
    }
}
