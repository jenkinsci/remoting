package hudson.remoting;

import java.util.concurrent.*;

/**
 * List of {@link CallableDecorator} that provides aggregated decoration operation.
 *
 * @author Kohsuke Kawaguchi
 */
class CallableDecoratorList extends CopyOnWriteArrayList<CallableDecorator> {
    <V> java.util.concurrent.Callable<V> wrapCallable(java.util.concurrent.Callable<V> r) {
        for (CallableDecorator d : this)
            r = applyDeccorator(r, d);
        return r;
    }

    private <V> java.util.concurrent.Callable<V> applyDeccorator(final java.util.concurrent.Callable<V> inner, final CallableDecorator filter) {
        return new java.util.concurrent.Callable<V>() {
            public V call() throws Exception {
                return filter.call(inner);
            }
        };
    }

    <V,T extends Throwable> Callable<V,T> wrapUserRequest(final Callable<V,T> c) {
        Callable<V,T> decorated = c;

        for (CallableDecorator d : this)
            decorated = d.userRequest(c,decorated);

        return decorated;
    }
}
