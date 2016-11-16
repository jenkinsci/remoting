/*
 *
 *  * The MIT License
 *  *
 *  * Copyright (c) 2016 CloudBees, Inc.
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy
 *  * of this software and associated documentation files (the "Software"), to deal
 *  * in the Software without restriction, including without limitation the rights
 *  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  * copies of the Software, and to permit persons to whom the Software is
 *  * furnished to do so, subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in
 *  * all copies or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  * THE SOFTWARE.
 *
 */

package org.jenkinsci.remoting.engine;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Tests of {@link WorkDirManager}
 * @author Oleg Nenashev.
 * @since TODO
 */
public class WorkDirManagerTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void shouldInitializeCorrectlyForExisitingDirectory() throws Exception {
        final File dir = tmpDir.newFolder("foo");

        // Probe files to confirm the directory does not get wiped
        final File probeFileInWorkDir = new File(dir, "probe.txt");
        FileUtils.write(probeFileInWorkDir, "Hello!");
        final File remotingDir = new File(dir, WorkDirManager.DEFAULT_INTERNAL_DIRECTORY);
        Files.createDirectory(remotingDir.toPath());
        final File probeFileInInternalDir = new File(remotingDir, "/probe.txt");
        FileUtils.write(probeFileInInternalDir, "Hello!");

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance().initializeWorkDir(dir, WorkDirManager.DEFAULT_INTERNAL_DIRECTORY);
        assertThat("The initialized internal directory differs from the expected one", createdDir.toFile(), equalTo(remotingDir));

        // Ensure that the files have not been wiped
        Assert.assertTrue("Probe file in the workDir has been wiped", probeFileInWorkDir.exists());
        Assert.assertTrue("Probe file in the internal directory has been wiped", probeFileInInternalDir.exists());
    }

    @Test
    public void shouldPerformMkdirsIfRequired() throws Exception {
        final File tmpDirFile = tmpDir.newFolder("foo");
        final File workDir = new File(tmpDirFile, "just/a/long/non/existent/path");
        Assert.assertFalse("The work dir should not exist in the test", workDir.exists());

        // Probe files to confirm the directory does not get wiped;
        final File remotingDir = new File(workDir, WorkDirManager.DEFAULT_INTERNAL_DIRECTORY);

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance().initializeWorkDir(workDir, WorkDirManager.DEFAULT_INTERNAL_DIRECTORY);
        assertThat("The initialized internal directory differs from the expected one", createdDir.toFile(), equalTo(remotingDir));
        Assert.assertTrue("Remoting internal directory should have been initialized", remotingDir.exists());
    }

    @Test
    public void shouldProperlyCreateDirectoriesForCustomInternalDirs() throws Exception {
        final String internalDirectoryName = "myRemotingLogs";
        final File tmpDirFile = tmpDir.newFolder("foo");
        final File workDir = new File(tmpDirFile, "just/another/path");
        Assert.assertFalse("The work dir should not exist in the test", workDir.exists());

        // Probe files to confirm the directory does not get wiped;
        final File remotingDir = new File(workDir, internalDirectoryName);

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance().initializeWorkDir(workDir, internalDirectoryName);
        assertThat("The initialized internal directory differs from the expected one", createdDir.toFile(), equalTo(remotingDir));
        Assert.assertTrue("Remoting internal directory should have been initialized", remotingDir.exists());
    }

    @Test
    public void shouldFailIfWorkDirIsAFile() throws IOException {
        File foo = tmpDir.newFile("foo");
        try {
            WorkDirManager.getInstance().initializeWorkDir(foo, WorkDirManager.DEFAULT_INTERNAL_DIRECTORY);
        } catch (IOException ex) {
            assertThat("Wrong exception message",
                    ex.getMessage(), containsString("The specified agent working directory path points to a non-directory file"));
            return;
        }
        Assert.fail("The directory has been initialized, but it should fail due to the conflicting file");
    }

    @Test
    public void shouldFailIfWorkDirIsNotExecutable() throws IOException {
        verifyDirectoryFlag(DirectoryFlag.NOT_EXECUTABLE);
    }

    @Test
    public void shouldFailIfWorkDirIsNotWritable() throws IOException {
        verifyDirectoryFlag(DirectoryFlag.NOT_WRITABLE);
    }


    @Test
    public void shouldFailIfWorkDirIsNotReadable() throws IOException {
        verifyDirectoryFlag(DirectoryFlag.NOT_READABLE);
    }

    @Test
    public void shouldNotSupportPathDelimitersAndSpacesInTheInternalDirName() throws IOException {
        File foo = tmpDir.newFolder("foo");

        assertAllocationFails(foo, " remoting ");
        assertAllocationFails(foo, " remoting");
        assertAllocationFails(foo, "directory with spaces");
        assertAllocationFails(foo, "nested/directory");
        assertAllocationFails(foo, "nested\\directory\\in\\Windows");
        assertAllocationFails(foo, "just&a&symbol&I&do&not&like");
    }

    private void assertAllocationFails(File workDir, String internalDirName) throws AssertionError {
        try {
            WorkDirManager.getInstance().initializeWorkDir(workDir, internalDirName);
        }  catch (IOException ex) {
            assertThat(ex.getMessage(), containsString("Remoting internal directory '" + internalDirName +"' is not compliant with the required format"));
            return;
        }
        Assert.fail("Initialization of WorkDirManager with invalid internal directory '" + internalDirName + "' should have failed");
    }

    private void verifyDirectoryFlag(DirectoryFlag flag) throws IOException, AssertionError {
        final File dir = tmpDir.newFolder("foo");
        flag.modifyFile(dir);
        try {
            WorkDirManager.getInstance().initializeWorkDir(dir, WorkDirManager.DEFAULT_INTERNAL_DIRECTORY);
        } catch (IOException ex) {
            assertThat("Wrong exception message for " + flag,
                    ex.getMessage(), containsString("The specified agent working directory should be fully accessible to the remoting executable"));
            return;
        }
        Assert.fail("The directory has been initialized, but it should fail since the target dir is " + flag);
    }

    private enum DirectoryFlag {
        NOT_WRITABLE,
        NOT_READABLE,
        NOT_EXECUTABLE;

        public void modifyFile(File file) throws AssertionError {
            switch (this) {
                case NOT_EXECUTABLE:
                    file.setExecutable(false);
                    break;
                case NOT_WRITABLE:
                    file.setWritable(false);
                    break;
                case NOT_READABLE:
                    file.setReadable(false);
                    break;
                default:
                    Assert.fail("Unsupported file mode " + this);
            }
        }
    }

}
