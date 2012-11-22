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

import hudson.remoting.ChannelRunner.InProcessCompatibilityMode;
import junit.framework.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.EmptyVisitor;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jvnet.hudson.test.Bug;

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
        DummyClassLoader cl = new DummyClassLoader(this.getClass().getClassLoader());
        Callable c = (Callable) cl.loadClass(CLASSNAME).newInstance();

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
        if (channelRunner instanceof InProcessCompatibilityMode)
            return;

        DummyClassLoader cl = new DummyClassLoader(this.getClass().getClassLoader());
        Callable c = (Callable) cl.loadClass(CLASSNAME).newInstance();
        assertSame(c.getClass().getClassLoader(), cl);

        channel.setProperty("test",c);

        channel.call(new RemotePropertyVerifier());
    }

    @Bug(6604)
    public void testRaceCondition() throws Throwable {
        DummyClassLoader parent = new DummyClassLoader(ClassRemotingTest.class.getClassLoader());
        DummyClassLoader child1 = new DummyClassLoader(parent, true);
        final Callable<Object,Exception> c1 = (Callable) child1.loadClass(CLASSNAME + "$Sub").newInstance();
        assertEquals(child1, c1.getClass().getClassLoader());
        assertEquals(parent, c1.getClass().getSuperclass().getClassLoader());
        DummyClassLoader child2 = new DummyClassLoader(parent, true);
        final Callable<Object,Exception> c2 = (Callable) child2.loadClass(CLASSNAME + "$Sub").newInstance();
        assertEquals(child2, c2.getClass().getClassLoader());
        assertEquals(parent, c2.getClass().getSuperclass().getClassLoader());
        ExecutorService svc = Executors.newFixedThreadPool(2);
        RemoteClassLoader.TESTING = true;
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
            RemoteClassLoader.TESTING = false;
        }
    }

    public static Test suite() throws Exception {
        return buildSuite(ClassRemotingTest.class);
    }

    private static class RemotePropertyVerifier implements Callable<Object, IOException> {
        public Object call() throws IOException {
            Object o = Channel.current().getRemoteProperty("test");
            assertEquals(o.getClass().getName(), CLASSNAME);
            assertTrue(Channel.class.getClassLoader() != o.getClass().getClassLoader());
            assertTrue(o.getClass().getClassLoader() instanceof RemoteClassLoader);
            return null;
        }
    }
}
