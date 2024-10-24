package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jenkinsci.remoting.CallableDecorator;

/**
 * {@link ExecutorService} that runs all the tasks in a given set of {@link CallableDecorator}s.
 * @author Kohsuke Kawaguchi
 * @see CallableDecorator
 * @see CallableDecoratorList
 */
class InterceptingExecutorService extends DelegatingExecutorService {
    private final CallableDecoratorList decorators;

    InterceptingExecutorService(ExecutorService base, CallableDecoratorList decorators) {
        super(base);
        this.decorators = decorators;
    }

    @Override
    public void execute(@NonNull Runnable command) {
        submit(command);
    }

    @Override
    @NonNull
    public <T> Future<T> submit(@NonNull Callable<T> task) {
        return super.submit(wrap(task));
    }

    @Override
    @NonNull
    public Future<?> submit(@NonNull Runnable task) {
        return submit(task, null);
    }

    @Override
    @NonNull
    public <T> Future<T> submit(@NonNull Runnable task, T result) {
        return super.submit(wrap(task, result));
    }

    @Override
    @NonNull
    public <T> List<Future<T>> invokeAll(@NonNull Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return super.invokeAll(wrap(tasks));
    }

    @Override
    @NonNull
    public <T> List<Future<T>> invokeAll(
            @NonNull Collection<? extends Callable<T>> tasks, long timeout, @NonNull TimeUnit unit)
            throws InterruptedException {
        return super.invokeAll(wrap(tasks), timeout, unit);
    }

    @Override
    @NonNull
    public <T> T invokeAny(@NonNull Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        return super.invokeAny(wrap(tasks));
    }

    @Override
    public <T> T invokeAny(@NonNull Collection<? extends Callable<T>> tasks, long timeout, @NonNull TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return super.invokeAny(wrap(tasks), timeout, unit);
    }

    private <V> Callable<V> wrap(final Runnable r, final V value) {
        return wrap(() -> {
            r.run();
            return value;
        });
    }

    private <T> Collection<Callable<T>> wrap(Collection<? extends Callable<T>> callables) {
        List<Callable<T>> r = new ArrayList<>();
        for (Callable<T> c : callables) {
            r.add(wrap(c));
        }
        return r;
    }

    private <V> Callable<V> wrap(Callable<V> r) {
        return decorators.wrapCallable(r);
    }
}
