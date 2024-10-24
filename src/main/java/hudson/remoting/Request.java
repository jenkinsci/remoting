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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Request/response pattern over {@link Channel}, the layer-1 service.
 *
 * <p>
 * This assumes that the receiving side has all the class definitions
 * available to de-serialize {@link Request}, just like {@link Command}.
 *
 * @author Kohsuke Kawaguchi
 * @see Response
 * @since 3.17
 */
public abstract class Request<RSP extends Serializable, EXC extends Throwable> extends Command {
    /**
     * Executed on a remote system to perform the task.
     *
     * @param channel
     *      The local channel. From the view point of the JVM that
     *      {@link #call(Channel)} made the call, this channel is
     *      the remote channel.
     * @return
     *      the return value will be sent back to the calling process.
     * @throws EXC
     *      The exception will be forwarded to the calling process.
     *      If no checked exception is supposed to be thrown, use {@link RuntimeException}.
     */
    @Nullable
    abstract RSP perform(Channel channel) throws EXC;

    /**
     * Uniquely identifies this request.
     * Used for correlation between request and response.
     */
    private final int id;

    /**
     * Set by the sender to the ID of the last I/O issued from the sender thread.
     * The receiver will ensure that this I/O operation has completed before carrying out the task.
     *
     * <p>
     * If the sender doesn't support this, the receiver will see 0.
     */
    private int lastIoId;

    private volatile Response<RSP, ? extends Throwable> response;

    transient long startTime;

    /**
     * While executing the call this is set to the handle of the execution.
     */
    transient volatile Future<?> future;

    /**
     * Set by {@link Response} to point to the I/O ID issued from the other side that this request needs to
     * synchronize with, before declaring the call to be complete.
     */
    /*package*/ transient volatile int responseIoId;

    /**
     *
     * @deprecated as of 2.16
     *      {@link PipeWriter} does this job better, but kept for backward compatibility to communicate
     *      with earlier version of remoting without losing the original fix to JENKINS-9189 completely.
     */
    @Deprecated
    /*package*/ transient volatile Future<?> lastIo;

    Request() {
        this(true);
    }

    @SuppressFBWarnings(
            value = "ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD",
            justification = "That is why we synchronize on the class.")
    Request(boolean recordCreatedAt) {
        super(recordCreatedAt);
        synchronized (Request.class) {
            id = nextId++;
        }
    }

    /**
     * Checks if the request can be executed on the channel.
     *
     * @param channel Channel
     * @throws IOException Error with explanation if the request cannot be executed.
     * @since 3.11
     */
    void checkIfCanBeExecutedOnChannel(Channel channel) throws IOException {
        final Throwable senderCloseCause = channel.getSenderCloseCause();
        if (senderCloseCause != null) {
            // Sender is closed, we won't be able to send anything
            throw new ChannelClosedException(channel, senderCloseCause);
        }
    }

    /**
     * Sends this request to a remote system, and blocks until we receives a response.
     *
     * @param channel
     *      The channel from which the request will be sent.
     * @throws InterruptedException
     *      If the thread is interrupted while it's waiting for the call to complete.
     * @throws IOException
     *      If there's an error during the communication.
     * @throws RequestAbortedException
     *      If the channel is terminated while the call is in progress.
     * @throws EXC
     *      If the {@link #perform(Channel)} throws an exception.
     */
    final RSP call(Channel channel) throws EXC, InterruptedException, IOException {
        // set the thread name to represent the channel we are blocked on,
        // so that thread dump would give us more useful information.
        Thread t = Thread.currentThread();
        String name = t.getName();
        try {
            t.setName(name + " / waiting for " + channel.getName() + " id=" + id);
            checkIfCanBeExecutedOnChannel(channel);
            lastIoId = channel.lastIoId();

            // Channel.send() locks channel, and there are other call sequences
            // (  like Channel.terminate()->Request.abort()->Request.onCompleted()  )
            // that locks channel -> request, so lock objects in the same order
            synchronized (channel) {
                synchronized (this) {
                    response = null;

                    channel.pendingCalls.put(id, this);
                    startTime = System.nanoTime();
                    channel.send(this);
                }
            }

            try {
                synchronized (this) {
                    while (response == null && !channel.isInClosed()) {
                        // I don't know exactly when this can happen, as pendingCalls are cleaned up by Channel,
                        // but in production I've observed that in rare occasion it can block forever, even after a
                        // channel
                        // is gone. So be defensive against that.
                        wait(30 * 1000);
                    }

                    if (response == null) {
                        // channel is closed and we still don't have a response
                        throw new RequestAbortedException(null);
                    }

                    if (lastIo != null) {
                        try {
                            lastIo.get();
                        } catch (ExecutionException e) {
                            // ignore the I/O error
                        }
                    }

                    try {
                        channel.pipeWriter.get(responseIoId).get();
                    } catch (ExecutionException e) {
                        // ignore the I/O error
                    }

                    Throwable exc = response.exception;

                    if (exc != null) {
                        channel.attachCallSiteStackTrace(exc);
                        throw (EXC) exc; // some versions of JDK fails to compile this line. If so, upgrade your JDK.
                    }

                    return response.returnValue;
                }
            } catch (InterruptedException e) {
                // if we are cancelled, abort the remote computation, too.
                // do this outside the "synchronized(this)" block to prevent locking Request and Channel in a wrong
                // order.
                synchronized (
                        channel) { // ... so that the close check and send won't be interrupted in the middle by a close
                    if (!channel.isOutClosed()) {
                        channel.send(new Cancel(
                                id)); // only send a cancel if we can, or else ChannelClosedException will mask the
                        // original cause
                    }
                }
                throw e;
            }
        } finally {
            t.setName(name);
        }
    }

    /**
     * Makes an invocation but immediately returns without waiting for the completion
     * (AKA asynchronous invocation.)
     *
     * @param channel
     *      The channel from which the request will be sent.
     * @return
     *      The {@link Future} object that can be used to wait for the completion.
     * @throws IOException
     *      If there's an error during the communication.
     */
    final hudson.remoting.Future<RSP> callAsync(final Channel channel) throws IOException {
        checkIfCanBeExecutedOnChannel(channel);

        response = null;
        lastIoId = channel.lastIoId();

        channel.pendingCalls.put(id, this);
        startTime = System.nanoTime();
        channel.send(this);

        return new hudson.remoting.Future<>() {

            private volatile boolean cancelled;

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                if (cancelled || isDone()) {
                    return false;
                }
                cancelled = true;
                if (mayInterruptIfRunning) {
                    try {
                        channel.send(new Cancel(id));
                    } catch (IOException x) {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }

            @Override
            public boolean isDone() {
                return isCancelled() || response != null;
            }

            @Override
            public RSP get() throws InterruptedException, ExecutionException {
                synchronized (Request.this) {
                    String oldThreadName = Thread.currentThread().getName();
                    Thread.currentThread().setName(oldThreadName + " for " + channel.getName() + " id=" + id);
                    try {
                        while (response == null) {
                            if (isCancelled()) {
                                throw new CancellationException();
                            }
                            if (channel.isInClosed()) {
                                throw new ExecutionException(new RequestAbortedException(null));
                            }
                            Request.this.wait(30 * 1000); // wait until the response arrives
                        }
                    } catch (InterruptedException e) {
                        try {
                            channel.send(new Cancel(id));
                        } catch (IOException e1) {
                            // couldn't cancel. ignore.
                        }
                        throw e;
                    } finally {
                        Thread.currentThread().setName(oldThreadName);
                    }

                    if (response.exception != null) {
                        throw new ExecutionException(response.exception);
                    }

                    return response.returnValue;
                }
            }

            @Override
            public RSP get(long timeout, @NonNull TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                synchronized (Request.this) {
                    // wait until the response arrives
                    // Note that the wait method can wake up for no reasons at all (AKA spurious wakeup),
                    long now = System.nanoTime();
                    long end = now + unit.toNanos(timeout);
                    while (response == null && (end - now > 0L)) {
                        if (isCancelled()) {
                            throw new CancellationException();
                        }
                        if (channel.isInClosed()) {
                            throw new ExecutionException(new RequestAbortedException(null));
                        }
                        Request.this.wait(Math.min(30 * 1000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - now))));
                        now = System.nanoTime();
                    }
                    if (response == null) {
                        throw new TimeoutException();
                    }

                    if (response.exception != null) {
                        throw new ExecutionException(response.exception);
                    }

                    return response.returnValue;
                }
            }
        };
    }

    /**
     * Called by the {@link Response} when we received it.
     */
    /*package*/ synchronized void onCompleted(Response<RSP, ? extends Throwable> response) {
        this.response = response;
        notifyAll();
    }

    /**
     * Aborts the processing. The calling thread will receive an exception.
     */
    /*package*/ void abort(IOException e) {
        onCompleted(new Response<>(this, id, 0, new RequestAbortedException(e)));
    }

    /**
     * Schedules the execution of this request.
     */
    @Override
    final void execute(final Channel channel) {
        channel.executingCalls.put(id, this);
        future = channel.executor.submit(new Runnable() {

            private int startIoId;

            private int calcLastIoId() {
                int endIoId = channel.lastIoId();
                if (startIoId == endIoId) {
                    return 0;
                }
                return endIoId;
            }

            @Override
            public void run() {
                String oldThreadName = Thread.currentThread().getName();
                Thread.currentThread().setName(oldThreadName + " for " + channel.getName() + " id=" + id);
                try {
                    Command rsp;
                    CURRENT.set(Request.this);
                    startIoId = channel.lastIoId();
                    try {
                        // make sure any I/O preceding this has completed
                        channel.pipeWriter.get(lastIoId).get();

                        RSP r = Request.this.perform(channel);
                        // normal completion
                        rsp = new Response<RSP, EXC>(Request.this, id, calcLastIoId(), r);
                    } catch (Throwable t) {
                        // error return
                        rsp = new Response<>(Request.this, id, calcLastIoId(), t);
                    } finally {
                        CURRENT.remove();
                    }
                    if (chainCause) {
                        rsp.chainCause(createdAt);
                    }

                    channel.send(rsp);
                } catch (IOException e) {
                    // communication error.
                    // this means the caller will block forever
                    if (e instanceof ChannelClosedException && !logger.isLoggable(Level.FINE)) {
                        // TODO if this is from CloseCommand reduce level to FINE
                        logger.info(() -> "Failed to send back a reply to the request " + Request.this + ": " + e);
                    } else {
                        logger.log(
                                Level.WARNING, e, () -> "Failed to send back a reply to the request " + Request.this);
                    }
                } finally {
                    channel.executingCalls.remove(id);
                    Thread.currentThread().setName(oldThreadName);
                }
            }
        });
    }

    /**
     * Next request ID.
     */
    private static int nextId = 0;

    private static final long serialVersionUID = 1L;

    private static final Logger logger = Logger.getLogger(Request.class.getName());

    /**
     * Set to true to chain {@link Command#createdAt} to track request/response relationship.
     * This will substantially increase the network traffic, but useful for debugging.
     */
    static boolean chainCause = Boolean.getBoolean(Request.class.getName() + ".chainCause");

    /**
     * Set to the {@link Request} object during {@linkplain #perform(Channel) the execution of the call}.
     *
     * @deprecated as of 2.16
     *      {@link PipeWriter} does this job better, but kept for backward compatibility to communicate
     *      with earlier version of remoting without losing the original fix to JENKINS-9189 completely.
     */
    @Deprecated
    /*package*/ static ThreadLocal<Request<?, ?>> CURRENT = new ThreadLocal<>();

    /*package*/ static int getCurrentRequestId() {
        Request<?, ?> r = CURRENT.get();
        return r != null ? r.id : 0;
    }

    /**
     * Interrupts the execution of the remote computation.
     */
    private static final class Cancel extends Command {
        private final int id;

        Cancel(int id) {
            this.id = id;
        }

        @Override
        protected void execute(Channel channel) {
            Request<?, ?> r = channel.executingCalls.get(id);
            if (r == null) {
                return; // already completed
            }
            Future<?> f = r.future;
            if (f != null) {
                f.cancel(true);
            }
        }

        @Override
        public String toString() {
            return "Request.Cancel";
        }

        private static final long serialVersionUID = -1709992419006993208L;
    }
}
