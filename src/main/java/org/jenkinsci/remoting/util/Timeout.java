/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
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

import hudson.remoting.VirtualChannel;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Allows operations to be limited in execution time.
 * For example, {@link VirtualChannel#call} could otherwise may hang forever.
 * Use in a {@code try}-with-resources block.
 * @author Jesse Glick
 * @author Oleg Nenashev
 * @since TODO
 */
@Restricted(NoExternalUse.class)
public class Timeout implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(Timeout.class.getName());

    private static final Timeout NO_TIMEOUT = new Timeout(null);
    
    @CheckForNull
    private final ScheduledFuture<?> task;

    private Timeout(@CheckForNull ScheduledFuture<?> task) {
        this.task = task;
    }

    @Override 
    public void close() {
        if (task != null) {
            task.cancel(false);
        }
    }

    /**
     * Sets optional timeout.
     * 
     * Disabled timeout is a not recommended mode for legacy operations.
     * A warning will be printed to the system log if it is defined.
     * 
     * @param timeout Timeout duration. If {@code null}, no timeout is set
     * @return Timeout object
     */
    public static Timeout optLimit(final @CheckForNull Duration timeout) {
        if (timeout != null) {
            return limit(timeout);
        } else {
            if (LOGGER.isLoggable(Level.CONFIG)) {
                LOGGER.log(Level.CONFIG, "Executing code block with disabled timeout. " +
                        "It may hang indefinitely.", new IllegalStateException("Timeout duration is null"));
            }
            return NO_TIMEOUT;
        }
    }
    
    public static Timeout limit(final @Nonnull Duration timeout) {
        return limit(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    
    public static Timeout limit(final long delay, final @Nonnull TimeUnit unit) {
        final Thread thread = Thread.currentThread();
        return new Timeout(Timer.get().schedule(new Runnable() {
            @Override public void run() {
                if (LOGGER.isLoggable(Level.FINE)) {
                    Throwable t = new Throwable();
                    t.setStackTrace(thread.getStackTrace());
                    LOGGER.log(Level.FINE, "Interrupting " + thread + " after " + delay + " " + unit, t);
                }
                thread.interrupt();
            }
        }, delay, unit));
    }

    // TODO JENKINS-32986 offer a variant that will escalate to Thread.stop

}