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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;

/**
 * Virtualized {@link Channel} that allows different implementations.
 *
 * @author Kohsuke Kawaguchi
 */
public interface VirtualChannel {
    /**
     * Makes a remote procedure call.
     *
     * <p>
     * Sends {@link Callable} to the remote system, executes it, and returns its result.
     * Such calls will be considered as user-space requests.
     * If the channel cannot execute the requests (e.g. when it is being closed),
     * the operations may be rejected even if the channel is still active.
     *
     * @param callable Callable to be executed
     * @throws InterruptedException
     *      If the current thread is interrupted while waiting for the completion.
     * @throws IOException
     *      If there's any error in the communication between {@link Channel}s.
     * @throws T User exception defined by the callable
     */
    <V, T extends Throwable> V call(Callable<V, T> callable) throws IOException, T, InterruptedException;

    /**
     * Makes an asynchronous remote procedure call.
     *
     * <p>
     * Similar to {@link #call(Callable)} but returns immediately.
     * The result of the {@link Callable} can be obtained through the {@link Future} object.
     * Such calls will be considered as user-space requests.
     * If the channel cannot execute the requests (e.g. when it is being closed),
     * the operations may be rejected even if the channel is still active.
     *
     * @return
     *      The {@link Future} object that can be used to wait for the completion.
     * @throws IOException
     *      If there's an error during the communication.
     */
    <V, T extends Throwable> Future<V> callAsync(final Callable<V, T> callable) throws IOException;

    /**
     * Performs an orderly shut down of this channel (and the remote peer.)
     *
     * @throws IOException
     *      if the orderly shut-down failed.
     */
    void close() throws IOException;

    /**
     * Waits for this {@link Channel} to be closed down.
     *
     * The close-down of a {@link Channel} might be initiated locally or remotely.
     *
     * @throws InterruptedException
     *      If the current thread is interrupted while waiting for the completion.
     * @since 1.300
     */
    void join() throws InterruptedException;

    /**
     * Waits for this {@link Channel} to be closed down, but only up the given milliseconds.
     * @param timeout Timeout in milliseconds
     * @throws InterruptedException
     *      If the current thread is interrupted while waiting for the completion.
     * @since 1.300
     */
    void join(long timeout) throws InterruptedException;

    /**
     * Exports an object for remoting to the other {@link Channel}
     * by creating a remotable proxy.
     * The returned reference must be kept if there is ongoing operation on the remote side.
     * Once it is released, the exported object will be deallocated as well.
     * Please keep in mind that the object may be also released earlier than expected by JVM
     * (e.g. see <a href="https://issues.jenkins-ci.org/browse/JENKINS-23271">JENKINS-23271</a>).
     *
     * @param instance
     *      Instance to be exported.
     *      {@code null} instances won't be exported to the remote instance.
     * <p>
     * All the parameters and return values must be serializable.
     * @param <T>
     *      Type
     * @param type
     *      Interface to be remoted.
     * @return
     *      the proxy object that implements {@code T}. This object can be transferred
     *      to the other {@link Channel}, and calling methods on it from the remote side
     *      will invoke the same method on the given local {@code instance} object.
     *      {@code null} if the input instance is {@code null}.
     */
    @Nullable
    <T> T export(Class<T> type, @CheckForNull T instance);

    /**
     * Blocks until all the I/O packets sent from remote is fully locally executed, then return.
     *
     * @since 1.402
     */
    void syncLocalIO() throws InterruptedException;
}
