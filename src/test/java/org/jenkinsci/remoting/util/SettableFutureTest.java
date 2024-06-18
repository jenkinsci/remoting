/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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
package org.jenkinsci.remoting.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * @author Stephen Connolly
 */
public class SettableFutureTest {
    private SettableFuture<String> future;

    private ExecutorService exec;
    private CountDownLatch latch;

    @Before
    public void setUp() {
        exec = Executors.newCachedThreadPool();
        latch = new CountDownLatch(1);
        future = SettableFuture.create();
        future.addListener(() -> latch.countDown(), exec);
        assertEquals(1, latch.getCount());
        assertFalse(future.isDone());
        assertFalse(future.isCancelled());
    }

    @After
    public void tearDown() {
        exec.shutdownNow();
    }

    @Test
    public void defaultState() {
        assertThrows(TimeoutException.class, () -> future.get(5, TimeUnit.MILLISECONDS));
    }

    @Test
    public void setValue() throws Exception {
        assertTrue(future.set("value"));
        assertCompletedFuture("value");
    }

    @Test
    public void setFailure() throws Exception {
        assertTrue(future.setException(new Exception("failure")));
        assertFailedFuture("failure");
    }

    @Test
    public void setFailureNull() throws Exception {
        assertThrows(NullPointerException.class, () -> future.setException(null));
        assertFalse(future.isDone());
        assertTrue(future.setException(new Exception("failure")));
        assertFailedFuture("failure");
    }

    @Test
    public void cancel() throws Exception {
        assertTrue(future.cancel(true));
        assertCancelledFuture();
    }

    @Test
    public void create() {
        SettableFuture<Integer> future = SettableFuture.create();
        assertFalse(future.isDone());
        assertFalse(future.isCancelled());
    }

    @Test
    public void setValue_simpleThreaded() throws Exception {
        SettableFuture<Integer> future = SettableFuture.create();
        assertTrue(future.set(42));
        // Later attempts to set the future should return false.
        assertFalse(future.set(23));
        assertFalse(future.setException(new Exception("bar")));
        // Check that the future has been set properly.
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
        assertEquals(42, (int) future.get());
    }

    @Test
    public void setException() {
        SettableFuture<Object> future = SettableFuture.create();
        Exception e = new Exception("foobarbaz");
        assertTrue(future.setException(e));
        // Later attempts to set the future should return false.
        assertFalse(future.set(23));
        assertFalse(future.setException(new Exception("quux")));
        // Check that the future has been set properly.
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());

        final ExecutionException ee = assertThrows(ExecutionException.class, future::get);
        assertSame(e, ee.getCause());
    }

    @Test
    public void cancel_beforeSet() {
        SettableFuture<Object> async = SettableFuture.create();
        async.cancel(true);
        assertFalse(async.set(42));
    }

    public void assertCompletedFuture(@Nullable Object expectedValue) throws InterruptedException, ExecutionException {
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());

        assertEquals(expectedValue, future.get());
    }

    public void assertCancelledFuture() throws InterruptedException, ExecutionException {
        assertTrue(future.isDone());
        assertTrue(future.isCancelled());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(future.isDone());
        assertTrue(future.isCancelled());

        assertThrows(
                "Future should throw CancellationException on cancel.",
                CancellationException.class,
                () -> future.get());
    }

    public void assertFailedFuture(@Nullable String message) throws InterruptedException {
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());

        final ExecutionException e =
                assertThrows("Future should rethrow the exception.", ExecutionException.class, () -> future.get());
        assertThat(e.getCause().getMessage(), is(message));
    }
}
