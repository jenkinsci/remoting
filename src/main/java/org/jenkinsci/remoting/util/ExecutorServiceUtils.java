/*
 * The MIT License
 *
 * Copyright (c) 2017 CloudBees, Inc.
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
package org.jenkinsci.remoting.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import javax.annotation.Nonnull;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Helper class for {@link ExecutorService} operations.
 * @author Oleg Nenashev
 */
@Restricted(NoExternalUse.class)
public class ExecutorServiceUtils {
    
    private ExecutorServiceUtils() {
        // The class cannot be constructed
    }
    
    /**
     * Submits a task to the executor service without further handling. 
     * The original {@link ExecutorService#submit(java.lang.Runnable)} method actually expects this return value 
     * to be handled, but this method explicitly relies on the external logic to handle the future operation.
     * Use on your own risk.
     * @param es Executor service
     * @param runnable Operation to be executed
     * @throws ExecutionRejectedException Execution is rejected by the executor service
     */
    @Nonnull
    @SuppressFBWarnings(value = "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE", 
            justification = "User of this API explicitly submits the task in the async mode on his own risk")
    public static void submitAsync(@Nonnull ExecutorService es, @Nonnull Runnable runnable) 
            throws ExecutionRejectedException {
        try {
            es.submit(runnable);
        } catch (RejectedExecutionException ex) {
            // Rethrow and make API users handle this.
            throw new ExecutionRejectedException(runnable, es, ex);
        }
    }
    
    /**
     * Wraps the runtime {@link RejectedExecutionException}.
     * The exception also caches the serializable metadata.
     */
    @Restricted(NoExternalUse.class)
    public static class ExecutionRejectedException extends Exception {

        private static final long serialVersionUID = 1L;
        private final String executorServiceDisplayName;
        private final String runnableDisplayName;
        
        public ExecutionRejectedException(Runnable runnable, ExecutorService es, String message) {
            super(message);
            this.executorServiceDisplayName = es.toString();
            this.runnableDisplayName = es.toString();
        }
        
        public ExecutionRejectedException(Runnable runnable, ExecutorService es, RejectedExecutionException cause) {
            super(cause);
            this.executorServiceDisplayName = es.toString();
            this.runnableDisplayName = es.toString();
        }

        public String getExecutorServiceDisplayName() {
            return executorServiceDisplayName;
        }

        public String getRunnableDisplayName() {
            return runnableDisplayName;
        }
    }
}
