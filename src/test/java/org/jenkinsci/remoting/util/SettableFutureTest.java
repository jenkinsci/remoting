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

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author Stephen Connolly
 */
public class SettableFutureTest {
    private SettableFuture<String> future;

    private ExecutorService exec;
    private CountDownLatch latch;

    @Before
    public void setUp() throws Exception {
        exec = Executors.newCachedThreadPool();
        latch = new CountDownLatch(1);
        future = SettableFuture.create();
        future.addListener(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
            }
        }, exec);
        assertEquals(1, latch.getCount());
        assertFalse(future.isDone());
        assertFalse(future.isCancelled());
    }

    @After
    public void tearDown() throws Exception {
        exec.shutdownNow();
    }

    @Test
    public void defaultState() throws Exception {
        try {
            future.get(5, TimeUnit.MILLISECONDS);
            fail();
        } catch (TimeoutException expected) {
            assertTrue(true);
        }
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
        try {
            future.setException(null);
            fail();
        } catch (NullPointerException expected) {
        }
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
    public void create() throws Exception {
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
    public void setException() throws Exception {
        SettableFuture<Object> future = SettableFuture.create();
        Exception e = new Exception("foobarbaz");
        assertTrue(future.setException(e));
        // Later attempts to set the future should return false.
        assertFalse(future.set(23));
        assertFalse(future.setException(new Exception("quux")));
        // Check that the future has been set properly.
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
        try {
            future.get();
            fail("Expected ExecutionException");
        } catch (ExecutionException ee) {
            assertSame(e, ee.getCause());
        }
    }

    @Test
    public void cancel_beforeSet() throws Exception {
        SettableFuture<Object> async = SettableFuture.create();
        async.cancel(true);
        assertFalse(async.set(42));
    }

    public void assertCompletedFuture(@Nullable Object expectedValue)
            throws InterruptedException, ExecutionException {
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());

        assertEquals(expectedValue, future.get());
    }

    public void assertCancelledFuture()
            throws InterruptedException, ExecutionException {
        assertTrue(future.isDone());
        assertTrue(future.isCancelled());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(future.isDone());
        assertTrue(future.isCancelled());

        try {
            future.get();
            fail("Future should throw CancellationException on cancel.");
        } catch (CancellationException expected) {
        }
    }

    public void assertFailedFuture(@Nullable String message)
            throws InterruptedException {
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());

        try {
            future.get();
            fail("Future should rethrow the exception.");
        } catch (ExecutionException e) {
            assertThat(e.getCause().getMessage(), is(message));
        }
    }

}
