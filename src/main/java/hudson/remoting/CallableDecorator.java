package hudson.remoting;

/**
 * Decorator on {@code Callable.call()} to filter the execution.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class CallableDecorator {
    /**
     * Used to decorate everything that execcutes in the channel as a result of a request from the other side,
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
     * provides opaque blob that itself may wrap the actual user-given operations.
     *
     * @param op
     *      The callable object given to {@link Channel#call(Callable)}
     * @param stem
     *      Computation that represents the invocation of 'op' as well as any additional decoration done by other
     *      {@link CallableDecorator}s.
     * @return
     *      Returns the a decorated {@link Callable} that represents the decorated computation,
     *      which normally execcutes some pre-processing, then delegate to the 'op', then perform some cleanup.
     *
     *      If there's nothing to filter, return 'op'.
     */
    public <V,T extends Throwable> Callable<V,T> userRequest(Callable<V,T> op, Callable<V,T> stem) {
        return stem;
    }
}
