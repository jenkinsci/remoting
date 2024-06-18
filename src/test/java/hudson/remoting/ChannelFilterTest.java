package hudson.remoting;

import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.concurrent.Callable;
import org.jenkinsci.remoting.CallableDecorator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Kohsuke Kawaguchi
 */
public class ChannelFilterTest {
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testFilter(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            channel.addLocalExecutionInterceptor(new CallableDecorator() {
                @Override
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

            Callable<Object> t = STORE::get;
            final Callable<Object> c = channel.export(Callable.class, t);

            assertEquals("x", channel.call(new CallableCallable(c)));
        });
    }

    private final ThreadLocal<Object> STORE = new ThreadLocal<>();

    private static class CallableCallable extends CallableBase<Object, Exception> {
        private final Callable<Object> c;

        public CallableCallable(Callable<Object> c) {
            this.c = c;
        }

        @Override
        public Object call() throws Exception {
            return c.call();
        }

        private static final long serialVersionUID = 1L;
    }

    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testBlacklisting(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            channel.addLocalExecutionInterceptor(new CallableDecorator() {
                @Override
                public <V, T extends Throwable> hudson.remoting.Callable<V, T> userRequest(
                        hudson.remoting.Callable<V, T> op, hudson.remoting.Callable<V, T> stem) {
                    if (op instanceof ShadyBusiness) {
                        throw new SecurityException("Rejecting " + op.getClass().getName());
                    }
                    return stem;
                }
            });

            // this direction is unrestricted
            assertEquals("gun", channel.call(new GunImporter()));

            // the other direction should be rejected
            final IOException e = assertThrows(IOException.class, () -> channel.call(new ReverseGunImporter()));
            assertEquals(
                    "Rejecting " + GunImporter.class.getName(),
                    findSecurityException(e).getMessage());
        });
    }

    private static SecurityException findSecurityException(Throwable x) {
        if (x instanceof SecurityException) {
            return (SecurityException) x;
        } else if (x == null) {
            throw new AssertionError("no SecurityException detected");
        } else {
            return findSecurityException(x.getCause());
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

    static class GunImporter extends CallableBase<String, IOException> implements ShadyBusiness {
        @Override
        public String call() {
            return "gun";
        }

        private static final long serialVersionUID = 1L;
    }

    static class ReverseGunImporter extends CallableBase<String, Exception> {
        @Override
        public String call() throws Exception {
            return Channel.currentOrFail().call(new GunImporter());
        }

        private static final long serialVersionUID = 1L;
    }
}
