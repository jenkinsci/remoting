/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link Future} implementation whose computation is carried out elsewhere.
 *
 * Call the {@link #set(Object)} method or {@link #set(Throwable)} method to set the value to the future.
 *
 * @author Kohsuke Kawaguchi
 */
public class AsyncFutureImpl<V> implements Future<V> {
    /**
     * Setting this field to true will indicate that the computation is completed.
     *
     * <p>
     * One of the following three fields also needs to be set at the same time.
     */
    private boolean completed;

    private V value;
    private Throwable problem;
    private boolean cancelled;

    public AsyncFutureImpl() {}

    public AsyncFutureImpl(V value) {
        set(value);
    }

    public AsyncFutureImpl(Throwable value) {
        set(value);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return false;
    }

    @Override
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    @Override
    public synchronized boolean isDone() {
        return completed;
    }

    @Override
    public synchronized V get() throws InterruptedException, ExecutionException {
        while (!completed) {
            wait();
        }
        if (problem != null) {
            throw new ExecutionException(problem);
        }
        if (cancelled) {
            throw new CancellationException();
        }
        return value;
    }

    @Override
    @CheckForNull
    public synchronized V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        // The accuracy of wait(long) operation is milliseconds anyway, but ok.
        long endWaitTime = System.nanoTime() + unit.toNanos(timeout);
        while (!completed) {
            long timeToWait = endWaitTime - System.nanoTime();
            if (timeToWait < 0) {
                break;
            }

            wait(timeToWait / 1000000, (int) (timeToWait % 1000000));
        }

        if (!completed) {
            throw new TimeoutException();
        }
        if (cancelled) {
            throw new CancellationException();
        }

        // TODO: we may be calling a complex get() implementation in the override, which may hang the code
        // The only missing behavior is "problem!=null" branch, but probably we cannot just replace the code without
        // an override issue risk.
        return get();
    }

    public synchronized void set(V value) {
        completed = true;
        this.value = value;
        notifyAll();
    }

    public synchronized void set(Throwable problem) {
        completed = true;
        this.problem = problem;
        notifyAll();
    }

    /**
     * Marks this task as cancelled.
     */
    public synchronized void setAsCancelled() {
        completed = true;
        cancelled = true;
        notifyAll();
    }
}
