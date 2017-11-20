/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting;

import junit.framework.Test;
import org.jvnet.hudson.test.Bug;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.EmptyVisitor;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Test class image forwarding.
 *
 * @author Kohsuke Kawaguchi
 */
public class ClassRemotingTest extends RmiTestBase {

    private static final String CLASSNAME = "hudson.rem0ting.TestCallable";

    public void test1() throws Throwable {
        // call a class that's only available on DummyClassLoader, so that on the remote channel
        // it will be fetched from this class loader and not from the system classloader.
        Callable c = (Callable) DummyClassLoader.apply(TestCallable.class);

        Object[] r = (Object[]) channel.call(c);

        System.out.println(r[0]);

        assertTrue(r[0].toString().startsWith("hudson.remoting.RemoteClassLoader@"));

        // make sure the bytes are what we are expecting
        System.out.println("Resource is "+((byte[])r[1]).length+" bytes");
        ClassReader cr = new ClassReader((byte[])r[1]);
        cr.accept(new EmptyVisitor(),false);

        // make sure cache is taking effect
        System.out.println(r[2]);
        System.out.println(r[3]);
        assertEquals(r[2],r[3]);
    }

    /**
     * Tests the use of user-defined classes in remote property access
     */
    public void testRemoteProperty() throws Exception {
        // this test cannot run in the compatibility mode without the multi-classloader serialization support,
        // because it uses the class loader specified during proxy construction.
        if (channelRunner instanceof InProcessCompatibilityRunner)
            return;

        DummyClassLoader cl = new DummyClassLoader(TestCallable.class);
        Callable c = (Callable) cl.load(TestCallable.class);
        assertSame(c.getClass().getClassLoader(), cl);

        channel.setProperty("test",c);

        channel.call(new RemotePropertyVerifier());
    }

    @Bug(6604)
    public void testRaceCondition() throws Throwable {
        DummyClassLoader parent = new DummyClassLoader(TestCallable.class);
        DummyClassLoader child1 = new DummyClassLoader(parent, TestCallable.Sub.class);
        final Callable<Object,Exception> c1 = (Callable) child1.load(TestCallable.Sub.class);
        assertEquals(child1, c1.getClass().getClassLoader());
        assertEquals(parent, c1.getClass().getSuperclass().getClassLoader());
        DummyClassLoader child2 = new DummyClassLoader(parent, TestCallable.Sub.class);
        final Callable<Object,Exception> c2 = (Callable) child2.load(TestCallable.Sub.class);
        assertEquals(child2, c2.getClass().getClassLoader());
        assertEquals(parent, c2.getClass().getSuperclass().getClassLoader());
        ExecutorService svc = Executors.newFixedThreadPool(2);
        RemoteClassLoader.TESTING_CLASS_LOAD = new SleepForASec();
        try {
            java.util.concurrent.Future<Object> f1 = svc.submit(new java.util.concurrent.Callable<Object>() {
                public Object call() throws Exception {
                    return channel.call(c1);
                }
            });
            java.util.concurrent.Future<Object> f2 = svc.submit(new java.util.concurrent.Callable<Object>() {
                public Object call() throws Exception {
                    return channel.call(c2);
                }
            });
            f1.get();
            f2.get();
        } finally {
            RemoteClassLoader.TESTING_CLASS_LOAD = null;
        }
    }

    private static final class SleepForASec implements Runnable {
        @Override
        public void run() {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // Nothing
            }
        }
    }

    @Bug(19453)
    public void testInterruptionOfClassCreation() throws Exception {
        DummyClassLoader parent = new DummyClassLoader(TestLinkage.B.class);
        final DummyClassLoader child1 = new DummyClassLoader(parent, TestLinkage.A.class);
        final DummyClassLoader child2 = new DummyClassLoader(child1, TestLinkage.class);
        final Callable<Object, Exception> c = (Callable) child2.load(TestLinkage.class);
        assertEquals(child2, c.getClass().getClassLoader());
        RemoteClassLoader.TESTING_CLASS_LOAD = new InterruptThirdInvocation();
        try {
            java.util.concurrent.Future<Object> f1 = scheduleCallableLoad(c);

            try {
                f1.get();
            } catch (ExecutionException ex) {
                // Expected
            }

            // verify that classes that we tried to load aren't irrevocably damaged and it's still available
            assertEquals(String.class, channel.call(c).getClass());
        } finally {
            RemoteClassLoader.TESTING_CLASS_LOAD = null;
        }
    }

    @Bug(36991)
    public void testInterruptionOfClassReferenceCreation() throws Exception {
        DummyClassLoader parent = new DummyClassLoader(TestLinkage.B.class);
        final DummyClassLoader child1 = new DummyClassLoader(parent, TestLinkage.A.class);
        final DummyClassLoader child2 = new DummyClassLoader(child1, TestLinkage.class);
        final Callable<Object, Exception> c = (Callable) child2.load(TestLinkage.class);
        assertEquals(child2, c.getClass().getClassLoader());
        RemoteClassLoader.TESTING_CLASS_REFERENCE_LOAD = new InterruptThirdInvocation();

        try {
            Future<Object> f1 = scheduleCallableLoad(c);

            try {
                f1.get();
            } catch (ExecutionException ex) {
                // Expected
            }

            // verify that classes that we tried to load aren't irrevocably damaged and it's still available
            assertEquals(String.class, channel.call(c).getClass());
        } finally {
            RemoteClassLoader.TESTING_CLASS_REFERENCE_LOAD = null;
        }
    }

    private Future<Object> scheduleCallableLoad(final Callable<Object, Exception> c) {
        ExecutorService svc = Executors.newSingleThreadExecutor();
        return svc.submit(new java.util.concurrent.Callable<Object>() {
            public Object call() throws Exception {
                try {
                    return channel.call(c);
                } catch (Throwable t) {
                    throw new Exception(t);
                }
            }
        });
    }

    private static class InterruptThirdInvocation implements Runnable {
        private int invocationCount = 0;
        @Override
        public void run() {
            invocationCount++;
            if (invocationCount == 3) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static Test suite() throws Exception {
        return buildSuite(ClassRemotingTest.class);
    }

    private static class RemotePropertyVerifier extends CallableBase<Object, IOException> {
        public Object call() throws IOException {
            Object o = getOpenChannelOrFail().getRemoteProperty("test");
            assertEquals(o.getClass().getName(), CLASSNAME);
            assertTrue(Channel.class.getClassLoader() != o.getClass().getClassLoader());
            assertTrue(o.getClass().getClassLoader() instanceof RemoteClassLoader);
            return null;
        }

        private static final long serialVersionUID = 1L;
    }
}
