package hudson.remoting;

import java.io.IOException;
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

    public void testBlacklisting() throws Exception {
        channel.addLocalExecutionInterceptor(new CallableFilter() {
            public <V> V call(Callable<V> callable) throws Exception {
                if (callable instanceof ShadyBusiness)
                    throw new SecurityException("Rejecting "+callable.getClass().getName());
                return callable.call();
            }
        });

        // this direction is unrestricted
        assertEquals("gun",channel.call(new GunImporter()));

        // the other direction should be rejected
        try {
            channel.call(new ReverseGunImporter());
            fail("should have failed");
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    /*
        Option 1:
                define CallableFilter2 that decorates h.r.Callable, not j.u.c.Callable
                like 'callUserRequest' maybe
        Option 2:
                define a separate interface.
     */

    private interface ShadyBusiness {}

    static class GunImporter implements hudson.remoting.Callable<String,IOException>, ShadyBusiness {
        public String call() {
            return "gun";
        }
    }

    static class ReverseGunImporter implements hudson.remoting.Callable<String, Exception> {
        public String call() throws Exception {
            return Channel.current().call(new GunImporter());
        }
    }
}
