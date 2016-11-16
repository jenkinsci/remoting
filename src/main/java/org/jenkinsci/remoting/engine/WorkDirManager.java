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
 * @author Oleg Nenashev
 * @since TODO
 */
@Restricted(NoExternalUse.class)
public class WorkDirManager {

    private static final Logger LOGGER = Logger.getLogger(WorkDirManager.class.getName());

    private static final WorkDirManager INSTANCE = new WorkDirManager();

    /**
     * Defines a default name of the internal data directory within the Working directory.
     */
    public static final String DEFAULT_INTERNAL_DIRECTORY = "remoting";

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
     * @return Initialized directory for internal files within workDir or {@code null} if it is disabled
     * @throws IOException Workspace allocation issue (e.g. the specified directory is not writable).
     *                     In such case Remoting should not start up at all.
     */
    @CheckForNull
    public Path initializeWorkDir(final @CheckForNull File workDir, final @Nonnull String internalDir) throws IOException {

        if (!internalDir.matches(SUPPORTED_INTERNAL_DIR_NAME_MASK)) {
            throw new IOException("Remoting internal directory '" + internalDir +"' is not compliant with the required format " + SUPPORTED_INTERNAL_DIR_NAME_MASK);
        }

        if (workDir == null) {
            LOGGER.log(Level.WARNING, "Agent working directory is not specified. Some functionality introduced in Remoting 3 may be disabled");
            return null;
        } else {
            if (workDir.exists()) {
                if (!workDir.isDirectory()) {
                    throw new IOException("The specified agent working directory path points to a non-directory file");
                }
                if (!workDir.canWrite() || !workDir.canRead() || !workDir.canExecute()) {
                    throw new IOException("The specified agent working directory should be fully accessible to the remoting executable (RWX)");
                }
            }

            // Now we create a subdirectory for remoting operations
            Path internalDirPath = new File(workDir, internalDir).toPath();
            Files.createDirectories(internalDirPath);
            LOGGER.log(Level.INFO, "Using {0} as a remoting working files directory", internalDirPath);
            return internalDirPath;
        }
    }
}
