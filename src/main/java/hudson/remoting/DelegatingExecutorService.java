package hudson.remoting;

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
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return base.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return base.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return base.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return base.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return base.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return base.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return base.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return base.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        base.execute(command);
    }
}
