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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.Issue;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Test class image forwarding.
 *
 * @author Kohsuke Kawaguchi
 */
public class ClassRemotingTest {

    static final String TESTCALLABLE_TRANSFORMED_CLASSNAME = "hudson.rem0ting.TestCallable";
    static final String TESTLINKAGE_TRANSFORMED_CLASSNAME = "hudson.rem0ting.TestLinkage";

    @Disabled("TODO flakes: Artificial testing interrupt.")
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void test1(ChannelRunner channelRunner) throws Throwable {
        channelRunner.withChannel(channel -> {
            // call a class that's only available on DummyClassLoader, so that on the remote channel
            // it will be fetched from this class loader and not from the system classloader.
            Callable<Object, Exception> callable =
                    (Callable<Object, Exception>) DummyClassLoader.apply(TestCallable.class);

            Object[] result = (Object[]) channel.call(callable);

            assertTestCallableResults(result);
            assertEquals(TESTCALLABLE_TRANSFORMED_CLASSNAME, callable.getClass().getName());
        });
    }

    /**
     * Tests the use of user-defined classes in remote property access
     */
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testRemoteProperty(ChannelRunner channelRunner) throws Exception {
        assumeFalse(
                channelRunner instanceof InProcessCompatibilityRunner,
                "this test cannot run in the compatibility mode without the multi-classloader serialization support,"
                        + "because it uses the class loader specified during proxy construction.");
        channelRunner.withChannel(channel -> {
            DummyClassLoader cl = new DummyClassLoader(TestCallable.class);
            Callable<Object, Exception> c = (Callable<Object, Exception>) cl.load(TestCallable.class);
            assertSame(c.getClass().getClassLoader(), cl);

            channel.setProperty("test", c);

            channel.call(new RemotePropertyVerifier());
        });
    }

    @Disabled("TODO flakes: Artificial testing interrupt.")
    @Issue("JENKINS-6604")
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testRaceCondition(ChannelRunner channelRunner) throws Throwable {
        channelRunner.withChannel(channel -> {
            DummyClassLoader parent = new DummyClassLoader(TestCallable.class);
            DummyClassLoader child1 = new DummyClassLoader(parent, TestCallable.Sub.class);
            final Callable<Object, Exception> c1 = (Callable<Object, Exception>) child1.load(TestCallable.Sub.class);
            assertEquals(child1, c1.getClass().getClassLoader());
            assertEquals(parent, c1.getClass().getSuperclass().getClassLoader());
            DummyClassLoader child2 = new DummyClassLoader(parent, TestCallable.Sub.class);
            final Callable<Object, Exception> c2 = (Callable<Object, Exception>) child2.load(TestCallable.Sub.class);
            assertEquals(child2, c2.getClass().getClassLoader());
            assertEquals(parent, c2.getClass().getSuperclass().getClassLoader());
            ExecutorService svc = Executors.newFixedThreadPool(2);
            RemoteClassLoader.TESTING_CLASS_LOAD = () -> Thread.sleep(1000);
            java.util.concurrent.Future<Object> f1 = svc.submit(() -> channel.call(c1));
            java.util.concurrent.Future<Object> f2 = svc.submit(() -> channel.call(c2));
            Object result1 = f1.get();
            Object result2 = f2.get();
            assertTestCallableResults((Object[]) result1);
            assertTestCallableResults((Object[]) result2);
        });
    }

    @Disabled("TODO flakes: Artificial testing interrupt.")
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testClassCreation_TestCallable(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            DummyClassLoader dummyClassLoader = new DummyClassLoader(TestCallable.class);
            final Callable<Object, Exception> callable =
                    (Callable<Object, Exception>) dummyClassLoader.load(TestCallable.class);
            java.util.concurrent.Future<Object> f1 = scheduleCallableLoad(channel, callable);

            Object result = f1.get();

            assertTestCallableResults((Object[]) result);
            Object loadResult = dummyClassLoader.load(TestCallable.class);
            assertEquals(
                    TESTCALLABLE_TRANSFORMED_CLASSNAME, loadResult.getClass().getName());
        });
    }

    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testClassCreation_TestLinkage(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            DummyClassLoader parent = new DummyClassLoader(TestLinkage.B.class);
            final DummyClassLoader child1 = new DummyClassLoader(parent, TestLinkage.A.class);
            final DummyClassLoader child2 = new DummyClassLoader(child1, TestLinkage.class);
            final Callable<Object, Exception> callable = (Callable<Object, Exception>) child2.load(TestLinkage.class);
            assertEquals(child2, callable.getClass().getClassLoader());
            java.util.concurrent.Future<Object> f1 = scheduleCallableLoad(channel, callable);

            Object result = f1.get();

            assertTestLinkageResults(channel, parent, child1, child2, callable, result);
        });
    }

    @Disabled("TODO flakes: Remote call on north failed")
    @Issue("JENKINS-61103")
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testClassCreation_TestStaticResourceReference(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            final DummyClassLoader dcl = new DummyClassLoader(TestStaticResourceReference.class);
            final Callable<Object, Exception> callable =
                    (Callable<Object, Exception>) dcl.load(TestStaticResourceReference.class);
            Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

            Object result = f1.get();
            assertTestStaticResourceReferenceResults(channel, callable, result);
        });
    }

    @Disabled("TODO flakes: Remote call on north failed")
    @Issue("JENKINS-61103")
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testClassCreation_TestFindResources(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            final DummyClassLoader dcl = new DummyClassLoader(TestStaticGetResources.class);
            final Callable<Object, Exception> callable =
                    (Callable<Object, Exception>) dcl.load(TestStaticGetResources.class);
            Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

            Object result = f1.get();
            assertTestStaticResourceReferenceResults(channel, callable, result);
        });
    }

    static void assertTestStaticResourceReferenceResults(
            Channel channel, Callable<Object, Exception> callable, Object result) throws Exception {
        assertEquals(String.class, channel.call(callable).getClass());
        assertTrue(result.toString().contains("impossible"));
    }

    static Future<Object> scheduleCallableLoad(Channel channel, final Callable<Object, Exception> c) {
        ExecutorService svc = Executors.newSingleThreadExecutor();
        return svc.submit(() -> channel.call(c));
    }

    static void assertTestLinkageResults(
            Channel channel,
            DummyClassLoader parent,
            DummyClassLoader child1,
            DummyClassLoader child2,
            Callable<Object, Exception> callable,
            Object result)
            throws Exception {
        assertEquals(String.class, channel.call(callable).getClass());
        assertTrue(result.toString().startsWith(TESTLINKAGE_TRANSFORMED_CLASSNAME + "$B"));
        Object loadResult = parent.load(TestLinkage.B.class);
        assertEquals(
                TESTLINKAGE_TRANSFORMED_CLASSNAME + "$B", loadResult.getClass().getName());
        loadResult = child1.load(TestLinkage.A.class);
        assertEquals(
                TESTLINKAGE_TRANSFORMED_CLASSNAME + "$A", loadResult.getClass().getName());
        loadResult = child2.load(TestLinkage.class);
        assertEquals(TESTLINKAGE_TRANSFORMED_CLASSNAME, loadResult.getClass().getName());
    }

    private static void assertTestCallableResults(Object[] result) {
        assertTrue(result[0].toString().startsWith("hudson.remoting.RemoteClassLoader@"));

        // make sure the bytes are what we are expecting
        ClassReader cr = new ClassReader((byte[]) result[1]);
        cr.accept(new EmptyVisitor(), ClassReader.SKIP_DEBUG);

        // make sure cache is taking effect
        assertEquals(result[2], result[3]);
        assertTrue(result[2].toString().contains(TESTCALLABLE_TRANSFORMED_CLASSNAME.replace(".", "/") + ".class"));
    }

    private static class EmptyVisitor extends ClassVisitor {

        public EmptyVisitor() {
            super(Opcodes.ASM7);
        }
    }

    private static class RemotePropertyVerifier extends CallableBase<Object, IOException> {
        @Override
        public Object call() throws IOException {
            Object o = getOpenChannelOrFail().getRemoteProperty("test");
            assertEquals(o.getClass().getName(), TESTCALLABLE_TRANSFORMED_CLASSNAME);
            assertNotSame(Channel.class.getClassLoader(), o.getClass().getClassLoader());
            assertTrue(o.getClass().getClassLoader() instanceof RemoteClassLoader);
            return null;
        }

        private static final long serialVersionUID = 1L;
    }
}
