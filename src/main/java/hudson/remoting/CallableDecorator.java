package hudson.remoting;

import java.util.concurrent.Callable;

/**
 * Decorator on {@code Callable.call()} to filter the execution.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class CallableDecorator {

    public <V> V call(Callable<V> callable) throws Exception {
        return callable.call();
    }

    public <V,T extends Throwable> hudson.remoting.Callable<V,T> userRequest(hudson.remoting.Callable<V,T> callable, hudson.remoting.Callable<V,T> stem) {
        return stem;
    }
}
