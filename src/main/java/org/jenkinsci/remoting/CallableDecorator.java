package org.jenkinsci.remoting;

import hudson.remoting.Callable;
import hudson.remoting.Channel;

/**
 * Decorator on {@code Callable.call()} to filter the execution.
 *
 * @author Kohsuke Kawaguchi
 * @see Channel#addLocalExecutionInterceptor(CallableDecorator)
 */
public abstract class CallableDecorator {
    /**
     * Used to decorate everything that executes in the channel as a result of a request from the other side,
     * such as RPC executions on exported objects, user-provided {@link Callable} execution, pipe write, and so on.
     */
    public <V> V call(java.util.concurrent.Callable<V> callable) throws Exception {
        return callable.call();
    }

    /**
     * Used to specifically decorate user-provided {@link Callable} execution.
     *
     * Unlike {@link #call(java.util.concurrent.Callable)}, this method provides an opportunity
     * to inspect the actual {@link Callable} object given to {@link Channel#call(Callable)}
     * from the other side, whereas {@link #call(java.util.concurrent.Callable)} only
     * provides an opaque blob that itself may wrap the actual user-given operations.
     *
     * @param op
     *      The original callable object given to {@link Channel#call(Callable)}.
     * @param stem
     *      Computation that represents the invocation of {@code op} as well as any additional decoration done by other
     *      {@link CallableDecorator}s.
     * @return
     *      Returns the a decorated {@link Callable} that represents the decorated computation,
     *      which normally executes some pre-processing, then delegates to the {@code stem}, then performs some cleanup.
     *
     *      If there's nothing to filter, return {@code stem}.
     * @throws RuntimeException
     *      Any exception thrown from this method will be propagated to the other side as if the execution of
     *      the callable had failed with this exception.
     */
    public <V, T extends Throwable> Callable<V, T> userRequest(Callable<V, T> op, Callable<V, T> stem) {
        return stem;
    }
}
