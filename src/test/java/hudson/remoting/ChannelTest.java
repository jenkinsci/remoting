package hudson.remoting;

import hudson.remoting.util.GCTask;
import org.jvnet.hudson.test.Bug;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;
import org.jenkinsci.remoting.RoleChecker;

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

    @Bug(39150)
    public void testDiagnostics() throws Exception {
        StringWriter sw = new StringWriter();
        Channel.dumpDiagnosticsForAll(new PrintWriter(sw));
        System.out.println(sw);
        assertTrue(sw.toString().contains("Channel north"));
        assertTrue(sw.toString().contains("Channel south"));
        assertTrue(sw.toString().contains("Commands sent=0"));
        assertTrue(sw.toString().contains("Commands received=0"));
    }
    
    /**
     * Checks if {@link UserRequest}s can be executed during the pending close operation.
     * @throws Exception Test Error
     */
    @Bug(45023)
    public void testShouldNotAcceptUserRequestsWhenIsBeingClosed() throws Exception {
        
        // Create a sample request to the channel
        final Callable<Void, Exception> testPayload = new NeverEverCallable();
        UserRequest<Void, Exception> delayedRequest = new UserRequest<>(channel, testPayload);
        
        try (ChannelCloseLock lock = new ChannelCloseLock(channel)) {
            // Call Async
            assertFailsWithChannelClosedException(TestRunnable.forChannel_call(testPayload));
            assertFailsWithChannelClosedException(TestRunnable.forChannel_callAsync(testPayload));
            assertFailsWithChannelClosedException(TestRunnable.forUserRequest_constructor(testPayload));

            // Check if the previously created command also fails to execute
            assertFailsWithChannelClosedException(TestRunnable.forUserRequest_call(delayedRequest, testPayload));
            assertFailsWithChannelClosedException(TestRunnable.forUserRequest_callAsync(delayedRequest, testPayload));
        }
    }
    
    private static final class NeverEverCallable implements Callable<Void, Exception> {

        private static final long serialVersionUID = 1L;

        @Override
        public Void call() throws Exception {
            throw new AssertionError("This method should be never executed");
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
            throw new AssertionError("This method should be never executed");
        }
    }
    
    /**
     * Auto-closable wrapper, which puts the {@link Channel} into the pending close state.
     * This state is achieved by a deadlock of the customer request, which blocks {@link Channel#close()}.
     * Within this wrapper all methods requiring the Channel instance lock will hang till the timeout.
     */
    private static final class ChannelCloseLock implements AutoCloseable {

        final ExecutorService svc;
        final Channel channel;

        public ChannelCloseLock(final @Nonnull Channel channel) throws AssertionError, InterruptedException {
            this.svc = Executors.newFixedThreadPool(2);
            this.channel = channel;
            
            // Lock channel
            java.util.concurrent.Future<Void> lockChannel = svc.submit(new java.util.concurrent.Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    synchronized (channel) {
                        System.out.println("All your channel belongs to us");
                        Thread.sleep(Long.MAX_VALUE);
                        return null;
                    }
                }
            });

            // Try to close the channel in another task
            java.util.concurrent.Future<Void> closeChannel = svc.submit(new java.util.concurrent.Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    System.out.println("Trying to close the channel");
                    channel.close();
                    System.out.println("Channel is closed");
                    return null;
                }
            });

            // Check the state
            Thread.sleep(1000);
            System.out.println("Running the tests");
            assertTrue("Channel should be closing", channel.isClosingOrClosed());
            assertFalse("Channel should not be closed due to the lock", channel.isOutClosed());
        }
        
        @Override
        public void close() throws Exception {
            svc.shutdownNow();
        }
        
    }
    
    private abstract static class TestRunnable {
        public abstract void run(Channel channel) throws Exception, AssertionError;
        
        private static final TestRunnable forChannel_call(final Callable<Void, Exception> payload) {
            return new TestRunnable() {
                @Override
                public void run(Channel channel) throws Exception, AssertionError {
                    channel.call(payload);
                }
            };
        }
        
        private static final TestRunnable forChannel_callAsync(final Callable<Void, Exception> payload) {
            return new TestRunnable() {
                @Override
                public void run(Channel channel) throws Exception, AssertionError {
                    channel.callAsync(payload);
                }
            };
        }
        
        private static final TestRunnable forUserRequest_constructor(final Callable<Void, Exception> payload) {
            return new TestRunnable() {
                @Override
                public void run(Channel channel) throws Exception, AssertionError {
                    new UserRequest<Void, Exception>(channel, payload);
                }
            };
        }
        
        private static final TestRunnable forUserRequest_call(final UserRequest<Void, Exception> req, final Callable<Void, Exception> payload) {
            return new TestRunnable() {
                @Override
                public void run(Channel channel) throws Exception, AssertionError {
                    req.call(channel);
                }
            };
        }
        
        private static final TestRunnable forUserRequest_callAsync(final UserRequest<Void, Exception> req, final Callable<Void, Exception> payload) {
            return new TestRunnable() {
                @Override
                public void run(Channel channel) throws Exception, AssertionError {
                    req.callAsync(channel);
                }
            };
        }
    }
    
    private void assertFailsWithChannelClosedException(TestRunnable call) throws AssertionError {
        try {
            call.run(channel);
        } catch(Exception ex) {
            if (ex instanceof ChannelClosedException) {
                // Fine
                return;
            } else {
                throw new AssertionError("Expected ChannelClosedException, but got another exception", ex);
            }
        }
        fail("Expected ChannelClosedException, but the call has completed without any exception");
    }
}
