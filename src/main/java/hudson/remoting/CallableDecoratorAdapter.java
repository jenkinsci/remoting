package hudson.remoting;

import org.jenkinsci.remoting.CallableDecorator;

/**
 * Use {@link CallableFilter} as {@link CallableDecorator}
 * @author Kohsuke Kawaguchi
 */
class CallableDecoratorAdapter extends CallableDecorator {
    private final CallableFilter filter;

    public CallableDecoratorAdapter(CallableFilter filter) {
        this.filter = filter;
    }

    @Override
    public <V> V call(java.util.concurrent.Callable<V> callable) throws Exception {
        return filter.call(callable);
    }

    @Override
    public int hashCode() {
        return filter.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj != null && obj.getClass() == this.getClass()) {
            return ((CallableDecoratorAdapter) obj).filter.equals(filter);
        }
        return false;
    }
}
