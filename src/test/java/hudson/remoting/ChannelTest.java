package hudson.remoting;

import hudson.remoting.util.GCTask;
import org.jvnet.hudson.test.Bug;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.TimeUnit;

/**
 * @author Kohsuke Kawaguchi
 */
public class ChannelTest extends RmiTestBase {
    public void testCapability() {
        assertTrue(channel.remoteCapability.supportsMultiClassLoaderRPC());
    }

    @Bug(9050)
    public void testFailureInDeserialization() throws Exception {
        try {
            channel.call(new CallableImpl());
            fail();
        } catch (IOException e) {
//            e.printStackTrace();
            assertEquals("foobar",e.getCause().getCause().getMessage());
            assertTrue(e.getCause().getCause() instanceof ClassCastException);
        }
    }

    private static class CallableImpl extends CallableBase<Object,IOException> {
        public Object call() throws IOException {
            return null;
        }

        private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            throw new ClassCastException("foobar");
        }
    }

    /**
     * Objects exported during the request arg capturing is subject to caller auto-deallocation.
     */
    @Bug(10424)
    public void testExportCallerDeallocation() throws Exception {
        for (int i=0; i<100; i++) {
            final GreeterImpl g = new GreeterImpl();
            channel.call(new GreetingTask(g));
            assertEquals(g.name,"Kohsuke");
            assertTrue("in this scenario, auto-unexport by the caller should kick in.",
                    !channel.exportedObjects.isExported(g));
        }
    }

    /**
     * Objects exported outside the request context should be deallocated by the callee.
     */
    @Bug(10424)
    public void testExportCalleeDeallocation() throws Exception {
        for (int j=0; j<10; j++) {
            final GreeterImpl g = new GreeterImpl();
            channel.call(new GreetingTask(channel.export(Greeter.class,g)));
            assertEquals(g.name,"Kohsuke");
            boolean isExported = channel.exportedObjects.isExported(g);
            if (!isExported) {
                // it is unlikely but possible that GC happens on remote node
                // and 'g' gets unexported before we get to execute the above line
                // if so, try again. if we kept failing after a number of retries,
                // then it's highly suspicious that the caller is doing the deallocation,
                // which is a bug.
                System.out.println("Bitten by over-eager GC, will retry test");
                continue;
            }

            // now we verify that 'g' gets eventually unexported by remote.
            // to do so, we keep calling System.gc().
            for (int i=0; i<30 && channel.exportedObjects.isExported(g); i++) {
                System.out.println("Attempting to force GC on remote end");
                channel.call(new GCTask(true));
                Thread.sleep(100);
            }

            assertTrue(
                    "Object isn't getting unexported by remote",
                    !channel.exportedObjects.isExported(g));

            return;
        }

        fail("in this scenario, remote will unexport this");
    }

    public void testGetSetProperty() throws Exception {
        channel.setProperty("foo","bar");
        assertEquals("bar", channel.getProperty("foo"));
        assertEquals("bar",channel.waitForProperty("foo"));

        ChannelProperty<Class> typedProp = new ChannelProperty<Class>(Class.class,"a type-safe property");
        channel.setProperty(typedProp, Void.class);
        assertEquals(Void.class, channel.getProperty(typedProp));
        assertEquals(Void.class, channel.waitForProperty(typedProp));
    }

    public void testWaitForRemoteProperty() throws Exception {
        Future<Void> f = channel.callAsync(new WaitForRemotePropertyCallable());
        assertEquals("bar", channel.waitForRemoteProperty("foo"));
        f.get(1, TimeUnit.SECONDS);
    }

    private static class WaitForRemotePropertyCallable extends CallableBase<Void, Exception> {
        public Void call() throws Exception {
            Thread.sleep(500);
            Channel.current().setProperty("foo","bar");
            return null;
        }
    }

    public interface Greeter {
        void greet(String name);
    }

    private static class GreeterImpl implements Greeter, Serializable {
        String name;
        public void greet(String name) {
            this.name = name;
        }

        private Object writeReplace() {
            return Channel.current().export(Greeter.class,this);
        }
    }

    private static class GreetingTask extends CallableBase<Object, IOException> {
        private final Greeter g;

        public GreetingTask(Greeter g) {
            this.g = g;
        }

        public Object call() throws IOException {
            g.greet("Kohsuke");
            return null;
        }
    }


    public void testClassLoaderHolder() throws Exception {
        URLClassLoader ucl = new URLClassLoader(new URL[0]);
        ClassLoaderHolder h = channel.call(new Echo<ClassLoaderHolder>(new ClassLoaderHolder(ucl)));
        assertSame(ucl,h.get());
    }

    private static class Echo<T> extends CallableBase<T,RuntimeException> {
        private final T t;

        Echo(T t) {
            this.t = t;
        }

        public T call() throws RuntimeException {
            return t;
        }
    }
}
