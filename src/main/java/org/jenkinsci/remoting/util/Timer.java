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

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionHandler;
import javax.annotation.Nonnull;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Holds the {@link ScheduledExecutorService} for running all background tasks in Remoting.
 * This ExecutorService will create additional threads to execute due (enabled) tasks.
 *
 * Provides a minimal abstraction for locating the ScheduledExecutorService so that we
 * can modify it's behavior going forward. For instance, to add manageability/monitoring.
 *
 * @author Ryan Campbell
 * @author Jesse Glick
 * @author Oleg Nenashev
 * @since TODO
 */
@Restricted(NoExternalUse.class)
public class Timer {

    private static final Logger LOGGER = Logger.getLogger(Timer.class.getName());
    
    /**
     * The scheduled executor thread pool. 
     * This is initialized lazily since it may be created/shutdown many times when running the test suite.
     */
    private static ScheduledExecutorService executorService;

    private Timer() {};
    
    /**
     * Returns the scheduled executor service used by all timed tasks in Jenkins.
     *
     * @return the single {@link ScheduledExecutorService}.
     */
    @Nonnull
    public static synchronized ScheduledExecutorService get() {
        if (executorService == null) {
            // corePoolSize is set to 10, but will only be created if needed.
            // ScheduledThreadPoolExecutor "acts as a fixed-sized pool using corePoolSize threads"
            executorService =  new ErrorLoggingScheduledThreadPoolExecutor(10, new TimerThreadFactory());
        }
        return executorService;
    }

    /**
     * Shutdown the timer and throw it away.
     */
    public static synchronized void shutdown() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

    private static class TimerThreadFactory implements ThreadFactory {
        
        private final ThreadFactory core;
        private final AtomicInteger threadNum = new AtomicInteger();

        public TimerThreadFactory() {
            this(Executors.defaultThreadFactory());
        }

        public TimerThreadFactory(ThreadFactory core) {
            this.core = core;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = core.newThread(r);
            t.setDaemon(true);
            t.setName("RemotingTimerThread [#" + threadNum.incrementAndGet() + "]");
            return t;
        }
    }
    
    private static class ErrorLoggingScheduledThreadPoolExecutor extends ScheduledThreadPoolExecutor {

        ErrorLoggingScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory) {
            super(corePoolSize, threadFactory);
        }

        ErrorLoggingScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
            super(corePoolSize, threadFactory, handler);
        }

        @Override
        protected void afterExecute(Runnable r, Throwable t) {
            super.afterExecute(r, t);
            if (t == null && r instanceof Future<?>) {
                Future<?> f = (Future<?>) r;
                if (f.isDone()) { // TODO super Javadoc does not suggest this, but without it, we hang in FutureTask.awaitDone!
                    try {
                        f.get(/* just to be on the safe side, do not wait */0, TimeUnit.NANOSECONDS);
                    } catch (TimeoutException x) {
                        // should not happen, right?
                    } catch (CancellationException x) {
                        // probably best to ignore this
                    } catch (ExecutionException x) {
                        t = x.getCause();
                    } catch (InterruptedException x) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (t != null) {
                LOGGER.log(Level.WARNING, "Failure in task not wrapped in SafeTimerTask", t);
            }
        }

    }

}
