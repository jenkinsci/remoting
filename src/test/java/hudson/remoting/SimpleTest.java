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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Testing the basic features.
 *
 * @author Kohsuke Kawaguchi
 */
public class SimpleTest {

    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void test1(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            int r = channel.call(new Callable1());
            System.out.println("result=" + r);
            assertEquals(5, r);
        });
    }

    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void test1Async(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            Future<Integer> r = channel.callAsync(new Callable1());
            System.out.println("result=" + r.get());
            assertEquals(5, (int) r.get());
        });
    }

    private static class Callable1 extends CallableBase<Integer, RuntimeException> {
        @Override
        public Integer call() throws RuntimeException {
            System.err.println("invoked");
            return 5;
        }

        private static final long serialVersionUID = 1L;
    }

    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void test2(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            final RuntimeException e = assertThrows(RuntimeException.class, () -> channel.call(new Callable2()));
            assertEquals(e.getMessage(), "foo");
        });
    }

    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void test2Async(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            final ExecutionException e = assertThrows(ExecutionException.class, () -> {
                Future<Integer> r = channel.callAsync(new Callable2());
                r.get();
            });
            assertEquals(e.getCause().getMessage(), "foo");
        });
    }

    private static class Callable2 extends CallableBase<Integer, RuntimeException> {
        @Override
        public Integer call() throws RuntimeException {
            throw new RuntimeException("foo");
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * Makes sure that proxied object can be sent back to the origin and resolve correctly.
     */
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void test3(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            Foo c = new Foo() {};
            Foo r = channel.call(new Echo<>(channel.export(Foo.class, c)));
            assertSame(c, r);
        });
    }

    public interface Foo {}

    private static class Echo<T> extends CallableBase<T, RuntimeException> {
        private final T t;

        Echo(T t) {
            this.t = t;
        }

        @Override
        public T call() throws RuntimeException {
            return t;
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * Checks whether {@link Future#cancel} behaves according to spec.
     * Currently seems to be used by MavenBuilder.call and Proc.RemoteProc.kill
     * (in turn used by MercurialSCM.joinWithTimeout when polling on remote host).
     */
    // @Bug(4611)
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testCancellation(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            Cancellable task = new Cancellable();
            Future<Integer> r = channel.callAsync(task);
            r.cancel(true);

            assertThrows(CancellationException.class, r::get, "should not return normally");

            assertTrue(r.isCancelled());
            assertFalse(task.ran);
            // TODO ought to also test various other aspects: cancelling before start, etc.
        });
    }

    private static class Cancellable extends CallableBase<Integer, InterruptedException> {
        boolean ran;

        @Override
        public Integer call() throws InterruptedException {
            Thread.sleep(9999);
            ran = true;
            return 0;
        }

        private static final long serialVersionUID = 1L;
    }
}
