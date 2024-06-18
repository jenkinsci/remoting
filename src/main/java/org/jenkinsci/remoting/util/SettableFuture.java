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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.remoting.Future;
import java.util.AbstractMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.jcip.annotations.GuardedBy;

/**
 * A {@link Future} that can be completed.
 * <p>
 * Inspired by {@code com.google.common.util.concurrent.SettableFuture} which we cannot use in remoting because we
 * need to keep external dependencies to a minimum.
 *
 * @since 3.0
 */
public final class SettableFuture<V> implements ListenableFuture<V> {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(SettableFuture.class.getName());
    /**
     * Lock for the completion state. We could use an {@link AbstractQueuedSynchronizer} for even more performance
     * but likely not much faster than a lock for our use cases.
     */
    private final Object lock = new Object();
    /**
     * Flag to indicate completion.
     */
    @GuardedBy("lock")
    private boolean done;
    /**
     * Flag to indicate cancellation.
     */
    @GuardedBy("lock")
    private boolean cancelled;
    /**
     * The completed value (may be {@code null}.
     */
    @GuardedBy("lock")
    @Nullable
    private V value;
    /**
     * The completed {@link Throwable}.
     */
    @GuardedBy("lock")
    @CheckForNull
    private Throwable throwable;
    /**
     * The listeners to notify.
     */
    private final Queue<Map.Entry<Runnable, Executor>> listeners = new LinkedList<>();
    /**
     * Flag to indicate that the listeners have been/are being notified and thus
     * {@link #addListener(Runnable, Executor)} should execute immediately (which is OK as we do not guarantee order of
     * execution.
     */
    @GuardedBy("listeners")
    private boolean notified;

    /**
     * Creates a new {@link SettableFuture}.
     *
     * @param <V> generic type of value.
     * @return a new {@link SettableFuture}.
     */
    public static <V> SettableFuture<V> create() {
        return new SettableFuture<>();
    }

    /**
     * Use {@link #create()}.
     */
    private SettableFuture() {}

    /**
     * Completes the future with the supplied value.
     *
     * @param value the value (may be {@code null}.
     * @return {@code true} if the future is now completed, {@code false} if the future has already been completed.
     */
    public boolean set(@Nullable V value) {
        boolean result;
        synchronized (lock) {
            if (done) {
                result = false;
            } else {
                done = true;
                this.value = value;
                lock.notifyAll();
                result = true;
            }
        }
        if (result) {
            notifyListeners();
        }
        return result;
    }

    /**
     * Completes the future with the supplied exception.
     *
     * @param throwable the exception.
     * @return {@code true} if the future is now completed, {@code false} if the future has already been completed.
     */
    public boolean setException(@NonNull Throwable throwable) {
        Objects.requireNonNull(throwable);
        boolean result;
        synchronized (lock) {
            if (done) {
                result = false;
            } else {
                done = true;
                this.throwable = throwable;
                lock.notifyAll();
                result = true;
            }
        }
        if (result) {
            notifyListeners();
        }
        if (throwable instanceof Error) {
            throw (Error) throwable;
        }
        return result;
    }

    /**
     * Completes the future by cancellation.
     *
     * @param mayInterruptIfRunning ignored.
     * @return {@code true} if the future is now cancelled, {@code false} if the future has already been completed.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        boolean result;
        synchronized (lock) {
            if (done) {
                result = false;
            } else {
                done = true;
                this.cancelled = true;
                lock.notifyAll();
                result = true;
            }
        }
        if (result) {
            notifyListeners();
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isCancelled() {
        synchronized (lock) {
            return done && cancelled;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDone() {
        synchronized (lock) {
            return done;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V get() throws InterruptedException, ExecutionException {
        synchronized (lock) {
            while (!done) {
                lock.wait();
            }
            if (cancelled) {
                throw new CancellationException();
            }
            if (throwable != null) {
                throw new ExecutionException(throwable);
            }
            return value;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V get(long timeout, @NonNull TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long timeoutNanos = unit.toNanos(timeout);
        long start = System.nanoTime();
        synchronized (lock) {
            while (!done) {
                long elapsed = System.nanoTime() - start;
                if (elapsed > timeoutNanos) {
                    throw new TimeoutException();
                }
                long remaining = timeoutNanos - elapsed;
                lock.wait(remaining / 1000000, (int) (remaining % 1000000));
            }
            if (cancelled) {
                throw new CancellationException();
            }
            if (throwable != null) {
                throw new ExecutionException(throwable);
            }
            return value;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addListener(@NonNull Runnable listener, @NonNull Executor executor) {
        Objects.requireNonNull(listener);
        Objects.requireNonNull(executor);
        boolean executeImmediate = false;
        synchronized (listeners) {
            if (!notified) {
                listeners.add(new AbstractMap.SimpleImmutableEntry<>(listener, executor));
            } else {
                executeImmediate = true;
            }
        }

        if (executeImmediate) {
            try {
                executor.execute(listener);
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.SEVERE,
                        e,
                        () -> "RuntimeException while executing runnable " + listener + " with executor " + executor);
            }
        }
    }

    /**
     * Notifies all the listeners on initial completion.
     */
    private void notifyListeners() {
        synchronized (listeners) {
            if (notified) {
                return;
            }
            notified = true;
        }
        while (!this.listeners.isEmpty()) {
            Map.Entry<Runnable, Executor> entry = this.listeners.poll();
            try {
                entry.getValue().execute(entry.getKey());
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.SEVERE,
                        e,
                        () -> "RuntimeException while executing runnable " + entry.getKey() + " with executor "
                                + entry.getValue());
            }
        }
    }
}
