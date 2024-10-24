/*
 *  The MIT License
 *
 *  Copyright (c) 2016 CloudBees, Inc.
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 */

package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.remoting.TeeOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.jenkinsci.remoting.util.PathUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Performs working directory management in remoting.
 * Using this manager remoting can initialize its working directory and put the data there.
 * The structure of the directory is described in {@link DirType}.
 * @author Oleg Nenashev
 * @since 3.8
 */
@Restricted(NoExternalUse.class)
public class WorkDirManager {

    private static final Logger LOGGER = Logger.getLogger(WorkDirManager.class.getName());

    private static WorkDirManager INSTANCE = new WorkDirManager();

    /**
     * Default value for the behavior when the requested working directory is missing.
     * The default value is {@code false}, because otherwise agents would fail on the first startup.
     */
    public static final boolean DEFAULT_FAIL_IF_WORKDIR_IS_MISSING = false;

    /**
     * Regular expression, which declares restrictions of the remoting internal directory symbols
     */
    public static final String SUPPORTED_INTERNAL_DIR_NAME_MASK = "[a-zA-Z0-9._-]*";

    /**
     * Name of the standard System Property, which points to the {@code java.util.logging} config file.
     */
    public static final String JUL_CONFIG_FILE_SYSTEM_PROPERTY_NAME = "java.util.logging.config.file";

    // Status data
    boolean loggingInitialized;

    @CheckForNull
    private File loggingConfigFile = null;

    /**
     * Provides a list of directories, which should not be initialized in the Work Directory.
     */
    private final Set<DirType> disabledDirectories = new HashSet<>();

    /**
     * Cache of initialized directory locations.
     */
    private final Map<DirType, File> directories = new HashMap<>();

    private WorkDirManager() {
        // Cannot be instantiated outside
    }

    /**
     * Retrieves the instance of the {@link WorkDirManager}.
     * Currently the implementation is hardcoded, but it may change in the future.
     * @return Workspace manager
     */
    @NonNull
    public static WorkDirManager getInstance() {
        return INSTANCE;
    }

    /*package*/ static void reset() {
        INSTANCE = new WorkDirManager();
        LogManager.getLogManager().reset();
    }

    public void disable(@NonNull DirType dir) {
        disabledDirectories.add(dir);
    }

    @CheckForNull
    public File getLocation(@NonNull DirType type) {
        return directories.get(type);
    }

    /**
     * Get {@link Path} of the directory.
     * @param type Type of the directory
     * @return Path
     * @since 3.14
     * @throws IOException Invalid path, e.g. ig the root directory is incorrect
     */
    @CheckForNull
    public Path getLocationPath(@NonNull DirType type) throws IOException {
        File location = directories.get(type);
        return location != null ? PathUtils.fileToPath(location) : null;
    }

    /**
     * Sets path to the Logging JUL property file with logging settings.
     * @param configFile config file
     */
    public void setLoggingConfig(@NonNull File configFile) {
        this.loggingConfigFile = configFile;
    }

    /**
     * Gets path to the property file with JUL settings.
     * This method checks the value passed from {@link #setLoggingConfig(File)} first.
     * If the value is not specified, it also checks the standard {@code java.util.logging.config.file} System property.
     * @return Path to the logging config file.
     *         {@code null} if it cannot be found.
     */
    @CheckForNull
    public File getLoggingConfigFile() {
        if (loggingConfigFile != null) {
            return loggingConfigFile;
        }

        String property = System.getProperty(JUL_CONFIG_FILE_SYSTEM_PROPERTY_NAME);
        if (property != null && !property.trim().isEmpty()) {
            return new File(property);
        }

        return null;
    }

    // TODO: New interfaces should ideally use Path instead of File
    /**
     * Initializes the working directory for the agent.
     * Within the working directory the method also initializes a working directory for internal needs (like logging)
     * @param workDir Working directory
     * @param internalDir Name of the remoting internal data directory within the working directory.
     *                    The range of the supported symbols is restricted to {@link #SUPPORTED_INTERNAL_DIR_NAME_MASK}.
     * @param failIfMissing Fail the initialization if the workDir or internalDir are missing.
     *                      This option presumes that the workspace structure gets initialized previously in order to ensure that we do not start up with a borked instance
     *                      (e.g. if a mount gets disconnected).
     * @return Initialized directory for internal files within workDir or {@code null} if it is disabled
     * @throws IOException Workspace allocation issue (e.g. the specified directory is not writable).
     *                     In such case Remoting should not start up at all.
     */
    @CheckForNull
    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "Parameter supplied by user / administrator.")
    public Path initializeWorkDir(
            final @CheckForNull File workDir, final @NonNull String internalDir, final boolean failIfMissing)
            throws IOException {

        if (!internalDir.matches(SUPPORTED_INTERNAL_DIR_NAME_MASK)) {
            throw new IOException(String.format(
                    "Name of %s ('%s') is not compliant with the required format: %s",
                    DirType.INTERNAL_DIR, internalDir, SUPPORTED_INTERNAL_DIR_NAME_MASK));
        }

        if (workDir == null) {
            // TODO: this message likely suppresses the connection setup on CI
            // LOGGER.log(Level.WARNING, "Agent working directory is not specified. Some functionality introduced in
            // Remoting 3 may be disabled");
            return null;
        } else {
            // Verify working directory
            verifyDirectory(workDir, DirType.WORK_DIR, failIfMissing);
            directories.put(DirType.WORK_DIR, workDir);

            // Create a subdirectory for remoting operations
            final File internalDirFile = new File(workDir, internalDir);
            verifyDirectory(internalDirFile, DirType.INTERNAL_DIR, failIfMissing);
            directories.put(DirType.INTERNAL_DIR, internalDirFile);

            // Create the directory on-demand
            final Path internalDirPath = PathUtils.fileToPath(internalDirFile);
            Files.createDirectories(internalDirPath);
            LOGGER.log(Level.INFO, "Using {0} as a remoting work directory", internalDirPath);

            // Create components of the internal directory
            createInternalDirIfRequired(internalDirFile, DirType.JAR_CACHE_DIR);
            createInternalDirIfRequired(internalDirFile, DirType.LOGS_DIR);

            return internalDirPath;
        }
    }

    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "Value supplied by user / administrator.")
    private void createInternalDirIfRequired(File internalDir, DirType type) throws IOException {
        if (!disabledDirectories.contains(type)) {
            final File directory = new File(internalDir, type.getDefaultLocation());
            verifyDirectory(directory, type, false);
            Files.createDirectories(PathUtils.fileToPath(directory));
            directories.put(type, directory);
        } else {
            LOGGER.log(Level.FINE, "Skipping the disabled directory: {0}", type.getName());
        }
    }

    /**
     * Verifies that the directory is compliant with the specified requirements.
     * The directory is expected to have {@code RWX} permissions if exists.
     * @param dir Directory
     * @param type Type of the working directory component to be verified
     * @param failIfMissing Fail if the directory is missing
     * @throws IOException Verification failure
     */
    private static void verifyDirectory(@NonNull File dir, @NonNull DirType type, boolean failIfMissing)
            throws IOException {
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                throw new IOException(
                        "The specified " + type + " path points to a non-directory file: " + dir.getPath());
            }
            if (!dir.canWrite() || !dir.canRead() || !dir.canExecute()) {
                throw new IOException("The specified " + type
                        + " should be fully accessible to the remoting executable (RWX): " + dir.getPath());
            }
        } else if (failIfMissing) {
            throw new IOException("The " + type + " is missing, but it is expected to exist: " + dir.getPath());
        }
    }

    /**
     * Sets up logging subsystem in the working directory.
     * If the logging system is already initialized, do nothing
     * @param internalDirPath Path to the internal remoting directory within the WorkDir.
     *                        If this value and {@code overrideLogPath} are null, the logging subsystem woill not
     *                        be initialized at all
     * @param overrideLogPath Overrides the common location of the remoting log.
     *                        If specified, logging system will be initialized in the legacy way.
     *                        If {@code null}, the behavior is defined by {@code internalDirPath}.
     * @throws IOException Initialization error
     */
    public void setupLogging(@CheckForNull Path internalDirPath, @CheckForNull Path overrideLogPath)
            throws IOException {
        if (loggingInitialized) {
            // Do nothing, in Remoting initialization there may be two calls due to the
            // legacy -agentLog behavior implementation.
            LOGGER.log(Level.CONFIG, "Logging system has been already initialized");
            return;
        }

        final File configFile = getLoggingConfigFile();
        if (configFile != null) {
            // TODO: There is a risk of second configuration call in the case of java.util.logging.config.file, but it's
            // safe
            LOGGER.log(Level.FINE, "Reading Logging configuration from file: {0}", configFile);
            try (FileInputStream fis = new FileInputStream(configFile)) {
                LogManager.getLogManager().readConfiguration(fis);
            }
        }

        if (overrideLogPath != null) { // Legacy behavior
            LOGGER.log(
                    Level.INFO,
                    "Using {0} as an agent error log destination; output log will not be generated",
                    overrideLogPath);
            System.out.flush(); // Just in case the channel
            System.err.flush();
            System.setErr(legacyCreateTeeStream(System.err, overrideLogPath));
            this.loggingInitialized = true;
        } else if (internalDirPath != null) { // New behavior
            LOGGER.log(Level.INFO, "Both error and output logs will be printed to {0}", internalDirPath);
            System.out.flush();
            System.err.flush();

            // Also redirect JUL to files if custom logging is not specified
            final File internalDirFile = internalDirPath.toFile();
            createInternalDirIfRequired(internalDirFile, DirType.LOGS_DIR);
            final File logsDir = getLocation(DirType.LOGS_DIR);

            if (configFile == null) {
                final Logger rootLogger = Logger.getLogger("");
                final File julLog = new File(logsDir, "remoting.log");
                final FileHandler logHandler = new FileHandler(julLog.getAbsolutePath(), 10 * 1024 * 1024, 5, false);
                logHandler.setFormatter(new SimpleFormatter());
                logHandler.setLevel(Level.INFO);
                rootLogger.addHandler(logHandler);
            }

            this.loggingInitialized = true;
        }
    }

    @SuppressFBWarnings(
            value = "DM_DEFAULT_ENCODING",
            justification =
                    "It is a legacy logging mode. Relying on the default is not fine, but it just behaves as it used to behave for years")
    private PrintStream legacyCreateTeeStream(OutputStream ostream, Path destination) throws FileNotFoundException {
        return new PrintStream(new TeeOutputStream(ostream, new FileOutputStream(destination.toFile())));
    }

    /**
     * Defines components of the Working directory.
     * @since 3.8
     */
    @Restricted(NoExternalUse.class)
    public enum DirType {
        /**
         * Top-level entry of the working directory.
         */
        WORK_DIR("working directory", "", null),
        /**
         * Directory, which stores internal data of the Remoting layer itself.
         * This directory is located within {@link #WORK_DIR}.
         */
        INTERNAL_DIR("remoting internal directory", "remoting", WORK_DIR),

        /**
         * Directory, which stores the JAR Cache.
         */
        JAR_CACHE_DIR("JAR Cache directory", "jarCache", INTERNAL_DIR),

        /**
         * Directory, which stores logs.
         */
        LOGS_DIR("Log directory", "logs", INTERNAL_DIR);

        @NonNull
        private final String name;

        @NonNull
        private final String defaultLocation;

        @CheckForNull
        private final DirType parentDir;

        DirType(@NonNull String name, @NonNull String defaultLocation, @CheckForNull DirType parentDir) {
            this.name = name;
            this.defaultLocation = defaultLocation;
            this.parentDir = parentDir;
        }

        @Override
        public String toString() {
            return name;
        }

        @NonNull
        public String getDefaultLocation() {
            return defaultLocation;
        }

        @NonNull
        public String getName() {
            return name;
        }
    }
}
