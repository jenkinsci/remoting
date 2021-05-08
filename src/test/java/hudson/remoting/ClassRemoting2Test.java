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
import org.junit.After;
import org.jvnet.hudson.test.Issue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Remote class loading tests that don't work with the full set of test runners
 * specified in RmiTestBase for various reasons. These tests may not be valid in
 * all runner configurations.
 *
 * For example, tests that depend on the MULTI_CLASSLOADER capability don't work
 * with Capability.NONE. Tests that are forked in a different JVM cannot use
 * test resources.
 *
 * The full suite of runners may test configurations that are no longer interesting
 * or applicable. It doesn't make sense to expect class loading to work with
 * Capability.NONE.
 */
@WithRunner({
        InProcessRunner.class,
        NioSocketRunner.class,
        NioPipeRunner.class,
})
public class ClassRemoting2Test extends RmiTestBase {

    @After
    public void tearDown() throws Exception {
        super.tearDown();
        RemoteClassLoader.TESTING_CLASS_REFERENCE_LOAD = null;
        RemoteClassLoader.TESTING_CLASS_LOAD = null;
        RemoteClassLoader.TESTING_RESOURCE_LOAD = null;
        RemoteClassLoader.RETRY_SLEEP_DURATION_MILLISECONDS = 100;
    }

    @Issue("JENKINS-19453")
    public void testSingleInterruptionOfClassCreation() throws Exception {
        DummyClassLoader parent = new DummyClassLoader(TestLinkage.B.class);
        final DummyClassLoader child1 = new DummyClassLoader(parent, TestLinkage.A.class);
        final DummyClassLoader child2 = new DummyClassLoader(child1, TestLinkage.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) child2.load(TestLinkage.class);
        assertEquals(child2, callable.getClass().getClassLoader());
        RemoteClassLoader.RETRY_SLEEP_DURATION_MILLISECONDS = 1;
        RemoteClassLoader.MAX_RETRIES = 10;
        RemoteClassLoader.TESTING_CLASS_LOAD = new InterruptInvocation(3, 3);
        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        Object result = f1.get();

        // verify that classes that we tried to load aren't irrevocably damaged and it's still available
        ClassRemotingTest.assertTestLinkageResults(channel, parent, child1, child2, callable, result);
    }

    @Issue("JENKINS-19453")
    public void testMultipleInterruptionOfClassCreation() throws Exception {
        DummyClassLoader parent = new DummyClassLoader(TestLinkage.B.class);
        final DummyClassLoader child1 = new DummyClassLoader(parent, TestLinkage.A.class);
        final DummyClassLoader child2 = new DummyClassLoader(child1, TestLinkage.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) child2.load(TestLinkage.class);
        assertEquals(child2, callable.getClass().getClassLoader());
        RemoteClassLoader.RETRY_SLEEP_DURATION_MILLISECONDS = 1;
        RemoteClassLoader.MAX_RETRIES = 10;
        RemoteClassLoader.TESTING_CLASS_LOAD = new InterruptInvocation(3, 6);
        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        Object result = f1.get();

        // verify that classes that we tried to load aren't irrevocably damaged and it's still available
        ClassRemotingTest.assertTestLinkageResults(channel, parent, child1, child2, callable, result);
    }

    public void testContinuedInterruptionOfClassCreation() throws Exception {
        DummyClassLoader parent = new DummyClassLoader(TestLinkage.B.class);
        final DummyClassLoader child1 = new DummyClassLoader(parent, TestLinkage.A.class);
        final DummyClassLoader child2 = new DummyClassLoader(child1, TestLinkage.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) child2.load(TestLinkage.class);
        assertEquals(child2, callable.getClass().getClassLoader());
        RemoteClassLoader.RETRY_SLEEP_DURATION_MILLISECONDS = 1;
        RemoteClassLoader.MAX_RETRIES = 3;
        RemoteClassLoader.TESTING_CLASS_LOAD = new InterruptInvocation(3, 10);
        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        try {
            f1.get();
            fail("Should have timed out, exceeding the max retries.");
        } catch (ExecutionException ex) {
            // Expected when we exceed the retries.
        }
    }

    @Issue("JENKINS-36991")
    public void testSingleInterruptionOfClassReferenceCreation() throws Exception {
        DummyClassLoader parent = new DummyClassLoader(TestLinkage.B.class);
        final DummyClassLoader child1 = new DummyClassLoader(parent, TestLinkage.A.class);
        final DummyClassLoader child2 = new DummyClassLoader(child1, TestLinkage.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) child2.load(TestLinkage.class);
        assertEquals(child2, callable.getClass().getClassLoader());
        RemoteClassLoader.RETRY_SLEEP_DURATION_MILLISECONDS = 1;
        RemoteClassLoader.MAX_RETRIES = 10;
        RemoteClassLoader.TESTING_CLASS_REFERENCE_LOAD = new InterruptInvocation(3, 3);

        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        Object result = f1.get();

        // verify that classes that we tried to load aren't irrevocably damaged and it's still available
        ClassRemotingTest.assertTestLinkageResults(channel, parent, child1, child2, callable, result);
    }

    @Issue("JENKINS-36991")
    public void testMultipleInterruptionOfClassReferenceCreation() throws Exception {
        DummyClassLoader parent = new DummyClassLoader(TestLinkage.B.class);
        final DummyClassLoader child1 = new DummyClassLoader(parent, TestLinkage.A.class);
        final DummyClassLoader child2 = new DummyClassLoader(child1, TestLinkage.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) child2.load(TestLinkage.class);
        assertEquals(child2, callable.getClass().getClassLoader());
        RemoteClassLoader.RETRY_SLEEP_DURATION_MILLISECONDS = 1;
        RemoteClassLoader.MAX_RETRIES = 10;
        RemoteClassLoader.TESTING_CLASS_REFERENCE_LOAD = new InterruptInvocation(3, 6);

        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        Object result = f1.get();

        // verify that classes that we tried to load aren't irrevocably damaged and it's still available
        ClassRemotingTest.assertTestLinkageResults(channel, parent, child1, child2, callable, result);
    }

    public void testContinuedInterruptionOfClassReferenceCreation() throws Exception {
        DummyClassLoader parent = new DummyClassLoader(TestLinkage.B.class);
        final DummyClassLoader child1 = new DummyClassLoader(parent, TestLinkage.A.class);
        final DummyClassLoader child2 = new DummyClassLoader(child1, TestLinkage.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) child2.load(TestLinkage.class);
        assertEquals(child2, callable.getClass().getClassLoader());
        RemoteClassLoader.RETRY_SLEEP_DURATION_MILLISECONDS = 1;
        RemoteClassLoader.MAX_RETRIES = 3;
        RemoteClassLoader.TESTING_CLASS_REFERENCE_LOAD = new InterruptInvocation(3, 10);

        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        try {
            f1.get();
            fail("Should have timed out, exceeding the max retries.");
        } catch (ExecutionException ex) {
            // Expected when we exceed the retries.
        }
    }

    @Issue("JENKINS-61103")
    public void testSingleInterruptionOfClassInitializationWithStaticResourceReference() throws Exception {
        final DummyClassLoader dcl = new DummyClassLoader(TestStaticResourceReference.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) dcl.load(TestStaticResourceReference.class);
        // make sure we get a remote interruption exception on "getResource" call
        RemoteClassLoader.TESTING_RESOURCE_LOAD = new InterruptInvocation(1, 1);
        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        Object result = f1.get();
        // verify that classes that we tried to load aren't irrevocably damaged and it's still available
        ClassRemotingTest.assertTestStaticResourceReferenceResults(channel, callable, result);
    }

    @Issue("JENKINS-61103")
    public void testMultipleInterruptionOfClassInitializationWithStaticResourceReference() throws Exception {
        final DummyClassLoader dcl = new DummyClassLoader(TestStaticResourceReference.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) dcl.load(TestStaticResourceReference.class);
        // make sure we get a remote interruption exception on "getResource" call
        RemoteClassLoader.RETRY_SLEEP_DURATION_MILLISECONDS = 1;
        RemoteClassLoader.MAX_RETRIES = 10;
        RemoteClassLoader.TESTING_RESOURCE_LOAD = new InterruptInvocation(1, 5);
        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        Object result = f1.get();
        // verify that classes that we tried to load aren't irrevocably damaged and it's still available
        ClassRemotingTest.assertTestStaticResourceReferenceResults(channel, callable, result);
    }

    @Issue("JENKINS-61103")
    public void testContinuedInterruptionOfClassInitializationWithStaticResourceReference() throws Exception {
        final DummyClassLoader dcl = new DummyClassLoader(TestStaticResourceReference.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) dcl.load(TestStaticResourceReference.class);
        // make sure we get a remote interruption exception on "getResource" call
        RemoteClassLoader.RETRY_SLEEP_DURATION_MILLISECONDS = 1;
        RemoteClassLoader.MAX_RETRIES = 3;
        RemoteClassLoader.TESTING_RESOURCE_LOAD = new InterruptInvocation(1, 10);
        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        try {
            f1.get();
            fail("Should have timed out, exceeding the max retries.");
        } catch (ExecutionException ex) {
            // Expected when we exceed the retries.
        }
    }

    @Issue("JENKINS-61103")
    public void testSingleInterruptionOfFindResources() throws Exception {
        final DummyClassLoader dcl = new DummyClassLoader(TestStaticGetResources.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) dcl.load(TestStaticGetResources.class);
        // make sure we get a remote interruption exception on "findResources" call
        RemoteClassLoader.TESTING_RESOURCE_LOAD = new InterruptInvocation(1, 1);
        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        Object result = f1.get();
        // verify that classes that we tried to load aren't irrevocably damaged and it's still available
        ClassRemotingTest.assertTestStaticResourceReferenceResults(channel, callable, result);
    }

    @Issue("JENKINS-61103")
    public void testMultipleInterruptionOfFindResources() throws Exception {
        final DummyClassLoader dcl = new DummyClassLoader(TestStaticGetResources.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) dcl.load(TestStaticGetResources.class);
        // make sure we get a remote interruption exception on "getResource" call
        RemoteClassLoader.RETRY_SLEEP_DURATION_MILLISECONDS = 1;
        RemoteClassLoader.MAX_RETRIES = 10;
        RemoteClassLoader.TESTING_RESOURCE_LOAD = new InterruptInvocation(1, 5);
        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        Object result = f1.get();
        // verify that classes that we tried to load aren't irrevocably damaged and it's still available
        ClassRemotingTest.assertTestStaticResourceReferenceResults(channel, callable, result);
    }

    @Issue("JENKINS-61103")
    public void testContinuedInterruptionOfFindResources() throws Exception {
        final DummyClassLoader dcl = new DummyClassLoader(TestStaticGetResources.class);
        final Callable<Object, Exception> callable = (Callable<Object, Exception>) dcl.load(TestStaticGetResources.class);
        // make sure we get a remote interruption exception on "getResource" call
        RemoteClassLoader.RETRY_SLEEP_DURATION_MILLISECONDS = 1;
        RemoteClassLoader.MAX_RETRIES = 3;
        RemoteClassLoader.TESTING_RESOURCE_LOAD = new InterruptInvocation(1, 10);
        Future<Object> f1 = ClassRemotingTest.scheduleCallableLoad(channel, callable);

        try {
            f1.get();
            fail("Should have timed out, exceeding the max retries.");
        } catch (ExecutionException ex) {
            // Expected when we exceed the retries.
        }
    }

    private static class InterruptInvocation implements RemoteClassLoader.Interruptible {
        private int invocationCount = 0;
        private int beginInterrupt;
        private int endInterrupt;

        private InterruptInvocation(int beginInterrupt, int endInterrupt) {
            this.beginInterrupt = beginInterrupt;
            this.endInterrupt = endInterrupt;
        }

        @Override
        public void run() throws InterruptedException {
            invocationCount++;
            if (invocationCount >= beginInterrupt && invocationCount <= endInterrupt) {
                throw new InterruptedException("Artificial testing interrupt.");
            }
        }
    }

    public static Test suite() {
        return buildSuite(ClassRemoting2Test.class);
    }

}
