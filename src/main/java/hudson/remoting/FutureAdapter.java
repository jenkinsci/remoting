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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link Future} that converts the return type.
 *
 * @author Kohsuke Kawaguchi
 */
abstract class FutureAdapter<X, Y> implements Future<X> {
    protected final Future<Y> core;

    protected FutureAdapter(Future<Y> core) {
        this.core = core;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return core.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
        return core.isCancelled();
    }

    @Override
    public boolean isDone() {
        return core.isDone();
    }

    @Override
    public X get() throws InterruptedException, ExecutionException {
        return adapt(core.get());
    }

    @Override
    public X get(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return adapt(core.get(timeout, unit));
    }

    protected abstract X adapt(Y y) throws ExecutionException;
}
