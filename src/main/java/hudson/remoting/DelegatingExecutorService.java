package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    @NonNull
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
    public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        return base.awaitTermination(timeout, unit);
    }

    @Override
    @NonNull
    public <T> Future<T> submit(@NonNull Callable<T> task) {
        return base.submit(task);
    }

    @Override
    @NonNull
    public <T> Future<T> submit(@NonNull Runnable task, T result) {
        return base.submit(task, result);
    }

    @Override
    @NonNull
    public Future<?> submit(@NonNull Runnable task) {
        return base.submit(task);
    }

    @Override
    @NonNull
    public <T> List<Future<T>> invokeAll(@NonNull Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return base.invokeAll(tasks);
    }

    @Override
    @NonNull
    public <T> List<Future<T>> invokeAll(
            @NonNull Collection<? extends Callable<T>> tasks, long timeout, @NonNull TimeUnit unit)
            throws InterruptedException {
        return base.invokeAll(tasks, timeout, unit);
    }

    @Override
    @NonNull
    public <T> T invokeAny(@NonNull Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return base.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(@NonNull Collection<? extends Callable<T>> tasks, long timeout, @NonNull TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return base.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(@NonNull Runnable command) {
        base.execute(command);
    }
}
