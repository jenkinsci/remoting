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

    public void shutdown() {
        base.shutdown();
    }

    public List<Runnable> shutdownNow() {
        return base.shutdownNow();
    }

    public boolean isShutdown() {
        return base.isShutdown();
    }

    public boolean isTerminated() {
        return base.isTerminated();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return base.awaitTermination(timeout, unit);
    }

    public <T> Future<T> submit(Callable<T> task) {
        return base.submit(task);
    }

    public <T> Future<T> submit(Runnable task, T result) {
        return base.submit(task, result);
    }

    public Future<?> submit(Runnable task) {
        return base.submit(task);
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return base.invokeAll(tasks);
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return base.invokeAll(tasks, timeout, unit);
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return base.invokeAny(tasks);
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return base.invokeAny(tasks, timeout, unit);
    }

    public void execute(Runnable command) {
        base.execute(command);
    }
}
