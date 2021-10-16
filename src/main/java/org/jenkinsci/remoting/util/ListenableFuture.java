/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Future;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Extend {@link Future} with the capability to accept completion callbacks. If the future has completed when the
 * callback is
 * added, the callback is triggered immediately.
 * <p>
 * Inspired by {@code com.google.common.util.concurrent.ListenableFuture}.
 */
public interface ListenableFuture<V> extends Future<V> {
    /**
     * Registers a listener to be run. The listener will be run on the specified executor either when the
     * {@link Future}'s computation is complete or, if the computation is already complete, immediately.
     * There is no guaranteed ordering of execution of listeners, but any listener added through this method is
     * guaranteed to be called once the computation is complete.
     *
     * Exceptions thrown by a listener will be propagated up to the executor.
     * Any exception thrown during {@link Executor#execute(Runnable)}
     * (e.g., a {@link RejectedExecutionException} or an exception thrown by direct execution) will be caught and
     * logged.
     *
     * @param listener the listener to execute.
     * @param executor the executor to run the listener in.
     */
    void addListener(@NonNull Runnable listener, @NonNull Executor executor);
}
