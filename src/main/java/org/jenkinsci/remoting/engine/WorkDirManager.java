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

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Performs working directory management in remoting.
 * Using this manager remoting can initialize its working directory and put the data there.
 * The structure of the directory is described in {@link DirType}.
 * @author Oleg Nenashev
 * @since TODO
 */
@Restricted(NoExternalUse.class)
public class WorkDirManager {

    private static final Logger LOGGER = Logger.getLogger(WorkDirManager.class.getName());

    private static final WorkDirManager INSTANCE = new WorkDirManager();

    /**
     * Default value for the behavior when the requested working directory is missing.
     * The default value is {@code false}, because otherwise agents would fail on the first startup.
     */
    public static final boolean DEFAULT_FAIL_IF_WORKDIR_IS_MISSING = false;

    /**
     * Regular expression, which declares restrictions of the remoting internal directory symbols
     */
    public static final String SUPPORTED_INTERNAL_DIR_NAME_MASK = "[a-zA-Z0-9._-]*";

    private WorkDirManager() {
        // Cannot be instantinated outside
    }

    /**
     * Retrieves the instance of the {@link WorkDirManager}.
     * Currently the implementation is hardcoded, but it may change in the future.
     * @return Workspace manager
     */
    @Nonnull
    public static WorkDirManager getInstance() {
        return INSTANCE;
    }

    //TODO: New interfaces should ideally use Path instead of File
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
    public Path initializeWorkDir(final @CheckForNull File workDir, final @Nonnull String internalDir, final boolean failIfMissing) throws IOException {

        if (!internalDir.matches(SUPPORTED_INTERNAL_DIR_NAME_MASK)) {
            throw new IOException(String.format("Name of %s ('%s') is not compliant with the required format: %s",
                    DirType.INTERNAL_DIR, internalDir, SUPPORTED_INTERNAL_DIR_NAME_MASK));
        }

        if (workDir == null) {
            LOGGER.log(Level.WARNING, "Agent working directory is not specified. Some functionality introduced in Remoting 3 may be disabled");
            return null;
        } else {
            // Verify working directory
            verifyDirectory(workDir, DirType.WORK_DIR, failIfMissing);

            // Create a subdirectory for remoting operations
            final File internalDirFile = new File(workDir, internalDir);
            verifyDirectory(internalDirFile, DirType.INTERNAL_DIR, failIfMissing);

            // Create the directory on-demand
            final Path internalDirPath = internalDirFile.toPath();
            Files.createDirectories(internalDirPath);
            LOGGER.log(Level.INFO, "Using {0} as a remoting working files directory", internalDirPath);
            return internalDirPath;
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
    private static void verifyDirectory(@Nonnull File dir, @Nonnull DirType type, boolean failIfMissing) throws IOException {
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                throw new IOException("The specified " + type + " path points to a non-directory file: " + dir.getPath());
            }
            if (!dir.canWrite() || !dir.canRead() || !dir.canExecute()) {
                throw new IOException("The specified " + type + " should be fully accessible to the remoting executable (RWX): " + dir.getPath());
            }
        } else if (failIfMissing) {
            throw new IOException("The " + type + " is missing, but it is expected to exist: " + dir.getPath());
        }
    }

    /**
     * Defines components of the Working directory.
     * @since TODO
     */
    @Restricted(NoExternalUse.class)
    public enum DirType {
        /**
         * Top-level entry of the working directory.
         */
        WORK_DIR("working directory", ""),
        /**
         * Directory, which stores internal data of the remoting layer itself.
         * This directory is located within {@link #WORK_DIR}.
         */
        INTERNAL_DIR("remoting internal directory", "remoting");

        @Nonnull
        private final String name;

        @Nonnull
        private final String defaultLocation;

        DirType(String name, String defaultLocation) {
            this.name = name;
            this.defaultLocation = defaultLocation;
        }

        @Override
        public String toString() {
            return name;
        }

        @Nonnull
        public String getDefaultLocation() {
            return defaultLocation;
        }

        @Nonnull
        public String getName() {
            return name;
        }
    }
}
