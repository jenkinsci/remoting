/*
 *
 *  * The MIT License
 *  *
 *  * Copyright (c) 2016-2017 CloudBees, Inc.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.Issue;

/**
 * Tests of {@link WorkDirManager}
 * @author Oleg Nenashev.
 */
class WorkDirManagerTest {

    @TempDir
    private File tmpDir;

    @SuppressWarnings("unused")
    @RegisterExtension
    private final WorkDirManagerExtension mgr = new WorkDirManagerExtension();

    @Test
    void shouldInitializeCorrectlyForExistingDirectory() throws Exception {
        final File dir = newFolder(tmpDir, "foo");

        // Probe files to confirm the directory does not get wiped
        final Path probeFileInWorkDir = dir.toPath().resolve("probe.txt");
        Files.writeString(probeFileInWorkDir, "Hello!", StandardCharsets.UTF_8);
        final Path remotingDir = dir.toPath().resolve(WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation());
        Files.createDirectory(remotingDir);
        final Path probeFileInInternalDir = remotingDir.resolve("probe.txt");
        Files.writeString(probeFileInInternalDir, "Hello!", StandardCharsets.UTF_8);

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance()
                .initializeWorkDir(dir, WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation(), false);
        assertThat(
                "The initialized " + WorkDirManager.DirType.INTERNAL_DIR + " differs from the expected one",
                createdDir,
                equalTo(remotingDir));

        // Ensure that the files have not been wiped
        assertTrue(
                Files.exists(probeFileInWorkDir),
                "Probe file in the " + WorkDirManager.DirType.WORK_DIR + " has been wiped");
        assertTrue(
                Files.exists(probeFileInInternalDir),
                "Probe file in the " + WorkDirManager.DirType.INTERNAL_DIR + " has been wiped");

        // Ensure that sub directories are in place
        assertExists(WorkDirManager.DirType.JAR_CACHE_DIR);
        assertExists(WorkDirManager.DirType.LOGS_DIR);
    }

    @Test
    void shouldPerformMkdirsIfRequired() throws Exception {
        final File tmpDirFile = newFolder(tmpDir, "foo");
        final File workDir = new File(tmpDirFile, "just/a/long/non/existent/path");
        assertFalse(workDir.exists(), "The " + WorkDirManager.DirType.INTERNAL_DIR + " should not exist in the test");

        // Probe files to confirm the directory does not get wiped;
        final File remotingDir = new File(workDir, WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation());

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance()
                .initializeWorkDir(workDir, WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation(), false);
        assertThat(
                "The initialized " + WorkDirManager.DirType.INTERNAL_DIR + " differs from the expected one",
                createdDir.toFile(),
                equalTo(remotingDir));
        assertTrue(
                remotingDir.exists(),
                "Remoting " + WorkDirManager.DirType.INTERNAL_DIR + " should have been initialized");
    }

    @Test
    void shouldProperlyCreateDirectoriesForCustomInternalDirs() throws Exception {
        final String internalDirectoryName = "myRemotingLogs";
        final File tmpDirFile = newFolder(tmpDir, "foo");
        final File workDir = new File(tmpDirFile, "just/another/path");
        assertFalse(workDir.exists(), "The " + WorkDirManager.DirType.WORK_DIR + " should not exist in the test");

        // Probe files to confirm the directory does not get wiped;
        final File remotingDir = new File(workDir, internalDirectoryName);

        // Initialize and check the results
        final Path createdDir = WorkDirManager.getInstance().initializeWorkDir(workDir, internalDirectoryName, false);
        assertThat(
                "The initialized " + WorkDirManager.DirType.INTERNAL_DIR + " differs from the expected one",
                createdDir.toFile(),
                equalTo(remotingDir));
        assertTrue(
                remotingDir.exists(),
                "Remoting " + WorkDirManager.DirType.INTERNAL_DIR + " should have been initialized");
    }

    @Test
    void shouldFailIfWorkDirIsAFile() throws IOException {
        File foo = newFile(tmpDir, "foo");
        try {
            WorkDirManager.getInstance()
                    .initializeWorkDir(foo, WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation(), false);
        } catch (IOException ex) {
            assertThat(
                    "Wrong exception message",
                    ex.getMessage(),
                    containsString("The specified " + WorkDirManager.DirType.WORK_DIR
                            + " path points to a non-directory file"));
            return;
        }
        fail("The " + WorkDirManager.DirType.WORK_DIR
                + " has been initialized, but it should fail due to the conflicting file");
    }

    @Test
    void shouldFailIfWorkDirIsNotExecutable() throws IOException {
        verifyDirectoryFlag(WorkDirManager.DirType.WORK_DIR, DirectoryFlag.NOT_EXECUTABLE);
    }

    @Test
    void shouldFailIfWorkDirIsNotWritable() throws IOException {
        verifyDirectoryFlag(WorkDirManager.DirType.WORK_DIR, DirectoryFlag.NOT_WRITABLE);
    }

    @Test
    void shouldFailIfWorkDirIsNotReadable() throws IOException {
        verifyDirectoryFlag(WorkDirManager.DirType.WORK_DIR, DirectoryFlag.NOT_READABLE);
    }

    @Test
    void shouldFailIfInternalDirIsNotExecutable() throws IOException {
        verifyDirectoryFlag(WorkDirManager.DirType.INTERNAL_DIR, DirectoryFlag.NOT_EXECUTABLE);
    }

    @Test
    void shouldFailIfInternalDirIsNotWritable() throws IOException {
        verifyDirectoryFlag(WorkDirManager.DirType.INTERNAL_DIR, DirectoryFlag.NOT_WRITABLE);
    }

    @Test
    void shouldFailIfInternalDirIsNotReadable() throws IOException {
        verifyDirectoryFlag(WorkDirManager.DirType.INTERNAL_DIR, DirectoryFlag.NOT_READABLE);
    }

    @Test
    void shouldNotSupportPathDelimitersAndSpacesInTheInternalDirName() throws IOException {
        File foo = newFolder(tmpDir, "foo");

        assertAllocationFails(foo, " remoting ");
        assertAllocationFails(foo, " remoting");
        assertAllocationFails(foo, "directory with spaces");
        assertAllocationFails(foo, "nested/directory");
        assertAllocationFails(foo, "nested\\directory\\in\\Windows");
        assertAllocationFails(foo, "just&a&symbol&I&do&not&like");
    }

    @Test
    @Issue("JENKINS-39130")
    void shouldFailToStartupIf_WorkDir_IsMissing_andRequired() throws Exception {
        final File tmpDirFile = newFolder(tmpDir, "foo");
        final File workDir = new File(tmpDirFile, "just/a/long/non/existent/path");
        assertFalse(workDir.exists(), "The " + WorkDirManager.DirType.INTERNAL_DIR + " should not exist in the test");

        assertAllocationFailsForMissingDir(workDir, WorkDirManager.DirType.WORK_DIR);
    }

    @Test
    @Issue("JENKINS-39130")
    void shouldFailToStartupIf_InternalDir_IsMissing_andRequired() throws Exception {
        // Create only the working directory, not the nested one
        final File tmpDirFile = newFolder(tmpDir, "foo");
        final File workDir = new File(tmpDirFile, "just/a/long/non/existent/path");
        Files.createDirectories(workDir.toPath());

        assertAllocationFailsForMissingDir(workDir, WorkDirManager.DirType.INTERNAL_DIR);
    }

    @Test
    void shouldNotCreateLogsDirIfDisabled() throws Exception {
        final File tmpDirFile = newFolder(tmpDir, "foo");
        assertDoesNotCreateDisabledDir(tmpDirFile, WorkDirManager.DirType.LOGS_DIR);
    }

    @Test
    void shouldNotCreateJarCacheIfDisabled() throws Exception {
        final File tmpDirFile = newFolder(tmpDir, "foo");
        assertDoesNotCreateDisabledDir(tmpDirFile, WorkDirManager.DirType.JAR_CACHE_DIR);
    }

    @Test
    void shouldCreateLogFilesOnTheDisk() throws Exception {
        final File workDir = newFolder(tmpDir, "workDir");
        final WorkDirManager mngr = WorkDirManager.getInstance();
        mngr.initializeWorkDir(workDir, "remoting", false);
        mngr.setupLogging(mngr.getLocation(WorkDirManager.DirType.INTERNAL_DIR).toPath(), null);

        // Write something to logs
        String message = String.format("Just 4 test. My Work Dir is %s", workDir);
        Logger.getLogger(WorkDirManager.class.getName()).log(Level.INFO, message);

        // Ensure log files have been created
        File logsDir = mngr.getLocation(WorkDirManager.DirType.LOGS_DIR);
        assertFileLogsExist(logsDir, "remoting.log", 0);

        // Ensure the entry has been written
        Path log0 = logsDir.toPath().resolve("remoting.log.0");
        String contents = Files.readString(log0, StandardCharsets.UTF_8);
        assertThat("Log file " + log0 + " should contain the probe message", contents, containsString(message));
    }

    @Test
    void shouldUseLoggingSettingsFromFileDefinedByAPI() throws Exception {
        final File loggingConfigFile = new File(tmpDir, "julSettings.prop");
        doTestLoggingConfig(loggingConfigFile, true);
    }

    @Test
    void shouldUseLoggingSettingsFromFileDefinedBySystemProperty() throws Exception {
        final File loggingConfigFile = new File(tmpDir, "julSettings.prop");
        final String oldValue = System.setProperty(
                WorkDirManager.JUL_CONFIG_FILE_SYSTEM_PROPERTY_NAME, loggingConfigFile.getAbsolutePath());
        try {
            doTestLoggingConfig(loggingConfigFile, false);
        } finally {
            // TODO: Null check and setting empty string is a weird hack
            System.setProperty(WorkDirManager.JUL_CONFIG_FILE_SYSTEM_PROPERTY_NAME, oldValue != null ? oldValue : "");
        }
    }

    private void doTestLoggingConfig(File loggingConfigFile, boolean passToManager) throws Exception {
        final File workDir = newFolder(tmpDir, "workDir");
        final File customLogDir = newFolder(tmpDir, "mylogs");

        Properties p = new Properties();
        p.setProperty("handlers", "java.util.logging.FileHandler");
        p.setProperty(
                "java.util.logging.FileHandler.pattern",
                customLogDir.getAbsolutePath() + File.separator + "mylog.log.%g");
        p.setProperty("java.util.logging.FileHandler.limit", "81920");
        p.setProperty("java.util.logging.FileHandler.count", "5");

        // Create config file
        try (OutputStream out = new FileOutputStream(loggingConfigFile)) {
            p.store(out, "Just a config file");
        }

        // Init WorkDirManager
        final WorkDirManager mngr = WorkDirManager.getInstance();
        if (passToManager) {
            mngr.setLoggingConfig(loggingConfigFile);
        }
        mngr.initializeWorkDir(workDir, "remoting", false);
        mngr.setupLogging(mngr.getLocation(WorkDirManager.DirType.INTERNAL_DIR).toPath(), null);

        // Write something to logs
        String message = String.format("Just 4 test. My Work Dir is %s", workDir);
        Logger.getLogger(WorkDirManager.class.getName()).log(Level.INFO, message);

        // Assert that logs directory still exists, but has no default logs
        assertExists(WorkDirManager.DirType.LOGS_DIR);
        File defaultLog0 = new File(mngr.getLocation(WorkDirManager.DirType.LOGS_DIR), "remoting.log.0");
        assertFalse(
                defaultLog0.exists(),
                "Log settings have been passed from the config file, the default log should not exist: " + defaultLog0);

        // Assert that logs have been written to the specified custom destination
        assertFileLogsExist(customLogDir, "mylog.log", 1);
        Path log0 = customLogDir.toPath().resolve("mylog.log.0");
        String contents = Files.readString(log0, StandardCharsets.UTF_8);
        assertThat("Log file " + log0 + " should contain the probe message", contents, containsString(message));
    }

    private void assertFileLogsExist(File logsDir, String prefix, int logFilesNumber) {
        for (int i = 0; i < logFilesNumber; ++i) {
            File log = new File(logsDir, prefix + "." + i);
            assertTrue(log.exists(), "Log file should exist: " + log);
        }
    }

    private void assertAllocationFailsForMissingDir(File workDir, WorkDirManager.DirType expectedCheckFailure) {
        // Initialize and check the results
        try {
            WorkDirManager.getInstance()
                    .initializeWorkDir(workDir, WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation(), true);
        } catch (IOException ex) {
            assertThat(
                    "Unexpected exception message",
                    ex.getMessage(),
                    containsString("The " + expectedCheckFailure + " is missing, but it is expected to exist:"));
            return;
        }
        fail("The workspace allocation did not fail for the missing " + expectedCheckFailure);
    }

    private void assertAllocationFails(File workDir, String internalDirName) throws AssertionError {
        try {
            WorkDirManager.getInstance().initializeWorkDir(workDir, internalDirName, false);
        } catch (IOException ex) {
            assertThat(
                    ex.getMessage(),
                    containsString(String.format(
                            "Name of %s ('%s') is not compliant with the required format",
                            WorkDirManager.DirType.INTERNAL_DIR, internalDirName)));
            return;
        }
        fail("Initialization of WorkDirManager with invalid internal directory '" + internalDirName
                + "' should have failed");
    }

    private void assertExists(@NonNull WorkDirManager.DirType type) throws AssertionError {
        final File location = WorkDirManager.getInstance().getLocation(type);
        assertNotNull(location, "WorkDir Manager didn't provide location of " + type);
        assertTrue(location.exists(), "Cannot find the " + type + " directory: " + location);
    }

    private void assertDoesNotCreateDisabledDir(File workDir, WorkDirManager.DirType type)
            throws AssertionError, IOException {
        WorkDirManager instance = WorkDirManager.getInstance();
        instance.disable(type);
        instance.initializeWorkDir(workDir, "remoting", false);

        // Checks
        assertThat(
                "Directory " + type + " has been added to the cache. Expected WirkDirManager to ignore it",
                instance.getLocation(type),
                nullValue());
        File internalDir = instance.getLocation(WorkDirManager.DirType.INTERNAL_DIR);
        File expectedDir = new File(internalDir, type.getDefaultLocation());
        assertFalse(expectedDir.exists(), "The logs directoy should not exist");
    }

    private void verifyDirectoryFlag(WorkDirManager.DirType type, DirectoryFlag flag)
            throws IOException, AssertionError {
        assumeFalse(
                "root".equals(System.getProperty("user.name")),
                "need to be running as a regular user for file permission checks to be meaningful");
        final File dir = newFolder(tmpDir, "test-" + type.getClass().getSimpleName() + "-" + flag);

        boolean success = false;
        File dirToModify = dir;
        switch (type) {
            case WORK_DIR:
                success = flag.modifyFile(dir);
                break;
            case INTERNAL_DIR:
                // Then we create remoting dir and also modify it
                dirToModify = new File(dir, WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation());
                Files.createDirectory(dirToModify.toPath());
                success = flag.modifyFile(dirToModify);
                break;
            default:
                fail("Unsupported Directory type: " + type);
        }
        Assumptions.assumeTrue(success, String.format("Failed to modify flag %s of %s", flag, dirToModify));

        try {
            WorkDirManager.getInstance()
                    .initializeWorkDir(dir, WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation(), false);
        } catch (IOException ex) {
            assertThat(
                    "Wrong exception message for " + flag,
                    ex.getMessage(),
                    containsString("The specified " + type + " should be fully accessible to the remoting executable"));
            return;
        }
        fail("The directory has been initialized, but it should fail since the target dir is " + flag);
    }

    private enum DirectoryFlag {
        NOT_WRITABLE,
        NOT_READABLE,
        NOT_EXECUTABLE;

        /**
         * Modifies the file's flag.
         * @param file File to modify
         * @return {@code true} if the operation succeeds
         */
        public boolean modifyFile(File file) throws AssertionError {
            return switch (this) {
                case NOT_EXECUTABLE -> file.setExecutable(false);
                case NOT_WRITABLE -> file.setWritable(false);
                case NOT_READABLE -> file.setReadable(false);
            };
        }
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }

    private static File newFile(File parent, String child) throws IOException {
        File result = new File(parent, child);
        result.createNewFile();
        return result;
    }
}
