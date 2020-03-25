/*
 * The MIT License
 *
 * Copyright (c) 2016-2018, Schneider Electric, CloudBees, Inc.
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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * @author Etienne Bec
 */
public class UtilTest {

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    @SuppressWarnings("deprecation")
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

        // Fail to create aloud, do not try on Windows
        if (!Launcher.isWindows()) {
            try {
                File forbidden = new File("/proc/nonono");
                Util.mkdirs(forbidden);
                fail("The directory has been created when it should not: " + forbidden);
            } catch (IOException ex) {
                // Expected
            }
        }
    }
}
