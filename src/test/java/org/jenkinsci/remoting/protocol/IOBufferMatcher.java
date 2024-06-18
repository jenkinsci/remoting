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
package org.jenkinsci.remoting.protocol;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.CheckReturnValue;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;
import org.hamcrest.Matcher;

/**
 * An {@link IOBufferMatcher} can {@link #send(ByteBuffer)} and {@link #receive(ByteBuffer)} streams of data as
 * {@link ByteBuffer}s. All the received data is accumulated. When there is no more data to receive then the
 * {@link #close()} method should be called.
 * There are helper methods to {@link #awaitClose()} as well as to {@link #awaitByteContent(Matcher)} or
 * {@link #awaitStringContent(Matcher)}. If you just need to wait for something to happen on the receive end
 * then {@link #awaitSomething()} is what you want. All the {@literal await} methods have a time-limited version.
 * The accumulated received data can be accessed either via {@link #asByteArray()} for the raw bytes or
 * {@link #asString()} if the {@literal UTF-8} decoded string is desired.
 */
public abstract class IOBufferMatcher {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(IOBufferMatcher.class.getName());
    /**
     * A name to differentiate multiple instances.
     */
    private final String name;

    private final ByteArrayOutputStream recv = new ByteArrayOutputStream();
    private final WritableByteChannel channel = Channels.newChannel(recv);
    private final CompletableFuture<IOException> closed = new CompletableFuture<>();
    private final CountDownLatch anything = new CountDownLatch(1);
    private final Lock state = new ReentrantLock();
    private final Condition changed = state.newCondition();
    private final List<Closeable> closeables = new ArrayList<>(1);

    public IOBufferMatcher() {
        this(null);
    }

    public IOBufferMatcher(String name) {
        this.name = name;
    }

    public abstract void send(ByteBuffer data) throws IOException;

    public boolean isOpen() {
        return !closed.isDone();
    }

    /**
     * Closes the buffer.
     * @param cause Cause. If {@code null}, an artificial cause will be added
     */
    public void close(@CheckForNull IOException cause) throws IOException {
        // If the close cause is not specified, just add an artificial one to capture the stacktrace
        // We do not close IOHub in production often, so it does not impact the performance
        final IOException causeToReport = cause != null ? cause : new IOException("Close requested");
        if (name != null && LOGGER.isLoggable(Level.INFO)) {
            LOGGER.log(Level.INFO, String.format("[%s] Closed", name), causeToReport);
        }
        innerClose(causeToReport);
    }

    private void innerClose(@NonNull IOException cause) {
        if (!closed.isDone()) {
            closed.complete(cause);
            anything.countDown();
            state.lock();
            try {
                changed.signalAll();
            } finally {
                state.unlock();
            }
            synchronized (closeables) {
                for (Closeable c : closeables) {
                    IOUtils.closeQuietly(c);
                }
                closeables.clear();
            }
        }
    }

    /**
     * Closes the buffer.
     * @throws IOException Never happens in the current code
     * @deprecated Use {@link #close(IOException)} instead
     */
    @Deprecated
    public void close() throws IOException {
        close(null);
    }

    public void receive(@NonNull ByteBuffer data) {
        int r = data.remaining();
        if (name != null) {
            LOGGER.log(Level.INFO, "[{0}] Receiving {1} bytes", new Object[] {name, r});
        }
        try {
            channel.write(data);
            anything.countDown();
            state.lock();
            try {
                changed.signalAll();
            } finally {
                state.unlock();
            }
        } catch (IOException e) {
            // ignore
        }
        if (name != null) {
            LOGGER.log(Level.INFO, "[{0}] Received {1} bytes: «{2}»", new Object[] {name, r - data.remaining(), this});
        }
    }

    public byte[] asByteArray() {
        return recv.toByteArray();
    }

    public String asString() {
        return new String(asByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "SimpleBufferReceiver{" + "name='" + name + '\'' + ", content='" + asString() + '\'' + '}';
    }

    /**
     * Waits till the buffer is closed.
     *
     * This method will wait infinitely, so it's a responsibility of the API user to call it properly.
     * {@link #awaitClose(long, TimeUnit)} is recommended if you are not sure.
     * @throws InterruptedException Wait is interrupted.
     */
    public void awaitClose() throws InterruptedException {
        try {
            closed.get();
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Waits till the buffer is clsoed, with a timeout.
     * @param timeout Wait timeout
     * @param unit Timeout unit
     * @return {@code true} if the bugffer has been closed successfully, {@code false} otherwise.
     * @throws InterruptedException Wait has been interrupted.
     */
    @CheckReturnValue
    public boolean awaitClose(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            closed.get(timeout, unit);
            return true;
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            return false;
        }
    }

    public IOException getCloseCause() throws ExecutionException, InterruptedException {
        return closed.get();
    }

    public void awaitSomething() throws InterruptedException {
        anything.await();
    }

    public boolean awaitSomething(long timeout, TimeUnit unit) throws InterruptedException {
        return anything.await(timeout, unit);
    }

    public void awaitStringContent(Matcher<String> matcher) throws InterruptedException {
        state.lock();
        try {
            while (!matcher.matches(asString())) {
                changed.await();
            }
        } finally {
            state.unlock();
        }
    }

    public boolean awaitStringContent(Matcher<String> matcher, long timeout, TimeUnit unit)
            throws InterruptedException {
        long giveUp = System.nanoTime() + unit.toNanos(timeout);
        state.lock();
        try {
            long remaining;
            while (0 < (remaining = giveUp - System.nanoTime())) {
                if (matcher.matches(asString())) {
                    return true;
                }
                changed.await(remaining, TimeUnit.NANOSECONDS);
            }
            return false;
        } finally {
            state.unlock();
        }
    }

    public void awaitByteContent(Matcher<byte[]> matcher) throws InterruptedException {
        state.lock();
        try {
            while (!matcher.matches(asByteArray())) {
                changed.await();
            }
        } finally {
            state.unlock();
        }
    }

    public boolean awaitByteContent(Matcher<byte[]> matcher, long timeout, TimeUnit unit) throws InterruptedException {
        long giveUp = System.nanoTime() + unit.toNanos(timeout);
        state.lock();
        try {
            long remaining;
            while (0 < (remaining = giveUp - System.nanoTime())) {
                if (matcher.matches(asByteArray())) {
                    return true;
                }
                changed.await(remaining, TimeUnit.NANOSECONDS);
            }
            return false;
        } finally {
            state.unlock();
        }
    }
}
