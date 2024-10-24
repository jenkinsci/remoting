/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link VirtualChannel} that performs computation on the local JVM.
 *
 * @author Kohsuke Kawaguchi
 */
public class LocalChannel implements VirtualChannel {
    private final ExecutorService executor;

    public LocalChannel(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public <V, T extends Throwable> V call(Callable<V, T> callable) throws T {
        return callable.call();
    }

    @Override
    public <V, T extends Throwable> Future<V> callAsync(@NonNull final Callable<V, T> callable) {
        final java.util.concurrent.Future<V> f = executor.submit(() -> {
            try {
                return callable.call();
            } catch (Exception | Error t) {
                throw t;
            } catch (Throwable t) {
                throw new ExecutionException(t);
            }
        });

        return new Future<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return f.cancel(mayInterruptIfRunning);
            }

            @Override
            public boolean isCancelled() {
                return f.isCancelled();
            }

            @Override
            public boolean isDone() {
                return f.isDone();
            }

            @Override
            public V get() throws InterruptedException, ExecutionException {
                return f.get();
            }

            @Override
            public V get(long timeout, @NonNull TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                return f.get(timeout, unit);
            }
        };
    }

    @Override
    public void close() {
        // noop
    }

    @Override
    public void join() throws InterruptedException {
        // noop
    }

    @Override
    public void join(long timeout) throws InterruptedException {
        // noop
    }

    @Override
    public <T> T export(@NonNull Class<T> intf, T instance) {
        return instance;
    }

    @Override
    public void syncLocalIO() throws InterruptedException {
        // noop
    }
}
