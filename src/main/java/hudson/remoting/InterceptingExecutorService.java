package hudson.remoting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * {@link ExecutorService} that runs all the tasks in a given set of {@link CallableFilter}s.
 * @author Kohsuke Kawaguchi
 */
class InterceptingExecutorService extends DelegatingExecutorService {
    private final CopyOnWriteArrayList<CallableFilter> filters = new CopyOnWriteArrayList<CallableFilter>();

    InterceptingExecutorService(ExecutorService base) {
        super(base);
    }

    public void addFilter(CallableFilter filter) {
        filters.add(filter);
    }

    public void removeFilter(CallableFilter filter) {
        filters.remove(filter);
    }
    
    @Override
    public void execute(Runnable command) {
        submit(command);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return super.submit(wrap(task));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return submit(task, null);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return super.submit(wrap(task,result));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return super.invokeAll(wrap(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return super.invokeAll(wrap(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return super.invokeAny(wrap(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return super.invokeAny(wrap(tasks), timeout, unit);
    }

    private <V> Callable<V> wrap(final Runnable r, final V value) {
        return wrap(new Callable<V>() {
            public V call() throws Exception {
                r.run();
                return value;
            }
        });
    }

    private <T> Collection<Callable<T>> wrap(Collection<? extends Callable<T>> callables) {
        List<Callable<T>> r = new ArrayList<Callable<T>>();
        for (Callable<T> c : callables) {
            r.add(wrap(c));
        }
        return r;
    }

    private <V> Callable<V> wrap(Callable<V> r) {
        for (CallableFilter f : filters)
            r = applyFilter(r,f);
        return r;
    }
    
    private <V> Callable<V> applyFilter(final Callable<V> inner, final CallableFilter filter) {
        return new Callable<V>() {
            public V call() throws Exception {
                return filter.call(inner);
            }
        };
    }
}
