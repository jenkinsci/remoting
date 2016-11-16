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

import org.jenkinsci.remoting.engine.WorkDirManager.DirType;
import org.jvnet.hudson.test.Bug;

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
        final File remotingDir = new File(dir, DirType.INTERNAL_DIR.getDefaultLocation());
        Files.createDirectory(remotingDir.toPath());
        final File probeFileInInternalDir = new File(remotingDir, "/probe.txt");
        FileUtils.write(probeFileInInternalDir, "Hello!");

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance().initializeWorkDir(dir, DirType.INTERNAL_DIR.getDefaultLocation(), false);
        assertThat("The initialized " + DirType.INTERNAL_DIR + " differs from the expected one", createdDir.toFile(), equalTo(remotingDir));

        // Ensure that the files have not been wiped
        Assert.assertTrue("Probe file in the " + DirType.WORK_DIR + " has been wiped", probeFileInWorkDir.exists());
        Assert.assertTrue("Probe file in the " + DirType.INTERNAL_DIR + " has been wiped", probeFileInInternalDir.exists());
    }

    @Test
    public void shouldPerformMkdirsIfRequired() throws Exception {
        final File tmpDirFile = tmpDir.newFolder("foo");
        final File workDir = new File(tmpDirFile, "just/a/long/non/existent/path");
        Assert.assertFalse("The " +  DirType.INTERNAL_DIR + " should not exist in the test", workDir.exists());

        // Probe files to confirm the directory does not get wiped;
        final File remotingDir = new File(workDir, DirType.INTERNAL_DIR.getDefaultLocation());

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance().initializeWorkDir(workDir, DirType.INTERNAL_DIR.getDefaultLocation(), false);
        assertThat("The initialized " + DirType.INTERNAL_DIR + " differs from the expected one", createdDir.toFile(), equalTo(remotingDir));
        Assert.assertTrue("Remoting " + DirType.INTERNAL_DIR +  " should have been initialized", remotingDir.exists());
    }

    @Test
    public void shouldProperlyCreateDirectoriesForCustomInternalDirs() throws Exception {
        final String internalDirectoryName = "myRemotingLogs";
        final File tmpDirFile = tmpDir.newFolder("foo");
        final File workDir = new File(tmpDirFile, "just/another/path");
        Assert.assertFalse("The " + DirType.WORK_DIR + " should not exist in the test", workDir.exists());

        // Probe files to confirm the directory does not get wiped;
        final File remotingDir = new File(workDir, internalDirectoryName);

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance().initializeWorkDir(workDir, internalDirectoryName, false);
        assertThat("The initialized " + DirType.INTERNAL_DIR + " differs from the expected one", createdDir.toFile(), equalTo(remotingDir));
        Assert.assertTrue("Remoting " + DirType.INTERNAL_DIR + " should have been initialized", remotingDir.exists());
    }

    @Test
    public void shouldFailIfWorkDirIsAFile() throws IOException {
        File foo = tmpDir.newFile("foo");
        try {
            WorkDirManager.getInstance().initializeWorkDir(foo, DirType.INTERNAL_DIR.getDefaultLocation(), false);
        } catch (IOException ex) {
            assertThat("Wrong exception message",
                    ex.getMessage(), containsString("The specified " + DirType.WORK_DIR + " path points to a non-directory file"));
            return;
        }
        Assert.fail("The " + DirType.WORK_DIR + " has been initialized, but it should fail due to the conflicting file");
    }

    @Test
    public void shouldFailIfWorkDirIsNotExecutable() throws IOException {
        verifyDirectoryFlag(DirType.WORK_DIR, DirectoryFlag.NOT_EXECUTABLE);
    }

    @Test
    public void shouldFailIfWorkDirIsNotWritable() throws IOException {
        verifyDirectoryFlag(DirType.WORK_DIR, DirectoryFlag.NOT_WRITABLE);
    }


    @Test
    public void shouldFailIfWorkDirIsNotReadable() throws IOException {
        verifyDirectoryFlag(DirType.WORK_DIR, DirectoryFlag.NOT_READABLE);
    }

    @Test
    public void shouldFailIfInternalDirIsNotExecutable() throws IOException {
        verifyDirectoryFlag(DirType.INTERNAL_DIR, DirectoryFlag.NOT_EXECUTABLE);
    }

    @Test
    public void shouldFailIfInternalDirIsNotWritable() throws IOException {
        verifyDirectoryFlag(DirType.INTERNAL_DIR, DirectoryFlag.NOT_WRITABLE);
    }


    @Test
    public void shouldFailIfInternalDirIsNotReadable() throws IOException {
        verifyDirectoryFlag(DirType.INTERNAL_DIR, DirectoryFlag.NOT_READABLE);
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

    @Test
    @Bug(39130)
    public void shouldFailToStartupIf_WorkDir_IsMissing_andRequired() throws Exception {
        final File tmpDirFile = tmpDir.newFolder("foo");
        final File workDir = new File(tmpDirFile, "just/a/long/non/existent/path");
        Assert.assertFalse("The " +  DirType.INTERNAL_DIR + " should not exist in the test", workDir.exists());

        assertAllocationFailsForMissingDir(workDir, DirType.WORK_DIR);
    }

    @Test
    @Bug(39130)
    public void shouldFailToStartupIf_InternalDir_IsMissing_andRequired() throws Exception {
        // Create only the working directory, not the nested one
        final File tmpDirFile = tmpDir.newFolder("foo");
        final File workDir = new File(tmpDirFile, "just/a/long/non/existent/path");
        Files.createDirectories(workDir.toPath());

        assertAllocationFailsForMissingDir(workDir, DirType.INTERNAL_DIR);
    }

    private void assertAllocationFailsForMissingDir(File workDir, DirType expectedCheckFailure) {
        // Initialize and check the results
        try {
            WorkDirManager.getInstance().initializeWorkDir(workDir, DirType.INTERNAL_DIR.getDefaultLocation(), true);
        } catch (IOException ex) {
            assertThat("Unexpected exception message", ex.getMessage(),
                    containsString("The " + expectedCheckFailure + " is missing, but it is expected to exist:"));
            return;
        }
        Assert.fail("The workspace allocation did not fail for the missing " + expectedCheckFailure);
    }

    private void assertAllocationFails(File workDir, String internalDirName) throws AssertionError {
        try {
            WorkDirManager.getInstance().initializeWorkDir(workDir, internalDirName, false);
        }  catch (IOException ex) {
            assertThat(ex.getMessage(), containsString(String.format("Name of %s ('%s') is not compliant with the required format",
                    DirType.INTERNAL_DIR, internalDirName)));
            return;
        }
        Assert.fail("Initialization of WorkDirManager with invalid internal directory '" + internalDirName + "' should have failed");
    }

    private void verifyDirectoryFlag(DirType type, DirectoryFlag flag) throws IOException, AssertionError {
        final File dir = tmpDir.newFolder("test-" + type.getClass().getSimpleName() + "-" + flag);

        switch (type) {
            case WORK_DIR:
                flag.modifyFile(dir);
                break;
            case INTERNAL_DIR:
                // Then we create remoting dir and also modify it
                File remotingDir = new File(dir, DirType.INTERNAL_DIR.getDefaultLocation());
                remotingDir.mkdir();
                flag.modifyFile(remotingDir);
                break;
            default:
                Assert.fail("Unsupported Directory type: " + type);
        }

        try {
            WorkDirManager.getInstance().initializeWorkDir(dir, DirType.INTERNAL_DIR.getDefaultLocation(), false);
        } catch (IOException ex) {
            assertThat("Wrong exception message for " + flag,
                    ex.getMessage(), containsString("The specified " + type + " should be fully accessible to the remoting executable"));
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
