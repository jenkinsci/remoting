package hudson.remoting;

import java.util.concurrent.CopyOnWriteArrayList;
import org.jenkinsci.remoting.CallableDecorator;

/**
 * List of {@link CallableDecorator} that provides aggregated decoration operation.
 *
 * @author Kohsuke Kawaguchi
 */
class CallableDecoratorList extends CopyOnWriteArrayList<CallableDecorator> {
    <V> java.util.concurrent.Callable<V> wrapCallable(java.util.concurrent.Callable<V> r) {
        for (CallableDecorator d : this) {
            r = applyDecorator(r, d);
        }
        return r;
    }

    private <V> java.util.concurrent.Callable<V> applyDecorator(
            final java.util.concurrent.Callable<V> inner, final CallableDecorator filter) {
        return () -> filter.call(inner);
    }

    <V, T extends Throwable> Callable<V, T> wrapUserRequest(final Callable<V, T> c) {
        Callable<V, T> decorated = c;

        for (CallableDecorator d : this) {
            decorated = d.userRequest(c, decorated);
        }

        return decorated;
    }

    // this class isn't actually getting serialized, but this makes FindBugs happy
    private static final long serialVersionUID = 1L;
}
