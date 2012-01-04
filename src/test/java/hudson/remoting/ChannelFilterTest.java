package hudson.remoting;

import java.util.concurrent.Callable;

/**
 * @author Kohsuke Kawaguchi
 */
public class ChannelFilterTest extends RmiTestBase {
    public void testFilter() throws Exception {
        channel.addLocalExecutionInterceptor(new CallableFilter() {
            public <V> V call(Callable<V> callable) throws Exception {
                Object old = STORE.get();
                STORE.set("x");
                try {
                    return callable.call();
                } finally {
                    STORE.set(old);
                }
            }
        });

        Callable<Object> t = new Callable<Object>() {
            public Object call() throws Exception {
                return STORE.get();
            }
        };
        final Callable c = channel.export(Callable.class, t);
        
        assertEquals("x", channel.call(new CallableCallable(c)));
    }
    
    private final ThreadLocal<Object> STORE = new ThreadLocal<Object>();

    private static class CallableCallable implements hudson.remoting.Callable<Object, Exception> {
        private final Callable c;

        public CallableCallable(Callable c) {
            this.c = c;
        }

        public Object call() throws Exception {
           return c.call();
        }
    }
}
