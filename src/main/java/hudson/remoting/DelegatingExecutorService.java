package hudson.remoting;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * {@link ExecutorService} that delegates to another one.
 *
 * @author Kohsuke Kawaguchi
 */
class DelegatingExecutorService implements ExecutorService {
    private final ExecutorService base;

    public DelegatingExecutorService(ExecutorService base) {
        this.base = base;
    }

    @Override
    public void shutdown() {
        base.shutdown();
    }

    @Override
    @Nonnull
    public List<Runnable> shutdownNow() {
        return base.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return base.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return base.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
        return base.awaitTermination(timeout, unit);
    }

    @Override
    @Nonnull
    public <T> Future<T> submit(@Nonnull Callable<T> task) {
        return base.submit(task);
    }

    @Override
    @Nonnull
    public <T> Future<T> submit(@Nonnull Runnable task, T result) {
        return base.submit(task, result);
    }

    @Override
    @Nonnull
    public Future<?> submit(@Nonnull Runnable task) {
        return base.submit(task);
    }

    @Override
    @Nonnull
    public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return base.invokeAll(tasks);
    }

    @Override
    @Nonnull
    public <T> List<Future<T>> invokeAll(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException {
        return base.invokeAll(tasks, timeout, unit);
    }

    @Override
    @Nonnull
    public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return base.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(@Nonnull Collection<? extends Callable<T>> tasks, long timeout, @Nonnull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return base.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(@Nonnull Runnable command) {
        base.execute(command);
    }
}
