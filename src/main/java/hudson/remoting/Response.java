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
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Serializable;

/**
 * Request/response pattern over {@link Command}.
 *
 * This is layer 1.
 *
 * @author Kohsuke Kawaguchi
 * @see Request
 * @since 3.17
 */
public final class Response<RSP extends Serializable, EXC extends Throwable> extends Command {
    /**
     * ID of the {@link Request} for which
     */
    private final int id;

    /**
     * Set by the sender to the ID of the last I/O issued during the command execution.
     * The receiver will ensure that this I/O operation has completed before carrying out the task.
     *
     * <p>
     * If the sender doesn't support this, the receiver will see 0.
     *
     * @see PipeWriter
     */
    private final int lastIoId;

    final RSP returnValue;
    final EXC exception;

    @SuppressFBWarnings(
            value = "SE_TRANSIENT_FIELD_NOT_RESTORED",
            justification = "Only supposed to be defined on one side.")
    private transient long totalTime;

    @SuppressFBWarnings(
            value = "SE_TRANSIENT_FIELD_NOT_RESTORED",
            justification = "Bound after deserialization, in execute.")
    private transient Request<RSP, ? extends Throwable> request;

    Response(Request<RSP, ? extends Throwable> request, int id, int lastIoId, RSP returnValue) {
        this.request = request;
        this.id = id;
        this.lastIoId = lastIoId;
        this.returnValue = returnValue;
        this.exception = null;
    }

    Response(Request<RSP, ? extends Throwable> request, int id, int lastIoId, EXC exception) {
        this.request = request;
        this.id = id;
        this.lastIoId = lastIoId;
        this.returnValue = null;
        this.exception = exception;
    }

    /**
     * Notifies the waiting {@link Request}.
     */
    @Override
    void execute(Channel channel) {
        Request<RSP, ? extends Throwable> req = (Request<RSP, ? extends Throwable>) channel.pendingCalls.get(id);
        if (req == null) {
            return; // maybe aborted
        }
        req.responseIoId = lastIoId;

        req.onCompleted(this);
        channel.pendingCalls.remove(id);
        request = req;
        long startTime = req.startTime;
        if (startTime != 0) {
            long time = System.nanoTime() - startTime;
            totalTime = time;
            channel.notifyResponse(req, this, time);
        }
    }

    @Override
    public String toString() {
        return "Response" + (request != null ? ":" + request : "") + "("
                + (returnValue != null
                        ? returnValue.getClass().getName()
                        : exception != null ? exception.getClass().getName() : null)
                + ")";
    }

    /**
     * Obtains the matching request.
     * @return null if this response has not been processed successfully
     */
    public @CheckForNull Request<?, ?> getRequest() {
        return request;
    }

    /**
     * Gets the return value of the response.
     * @return null in case {@link #getException} is non-null
     */
    public @NonNull RSP getReturnValue() {
        return returnValue;
    }

    /**
     * Gets the exception thrown by the response.
     * @return null in case {@link #getReturnValue} is non-null
     */
    public @NonNull EXC getException() {
        return exception;
    }

    /**
     * Gets the total time taken on the local side to send the request and receive the response.
     * @return the total time in nanoseconds, or zero if unknown, including if this response is being sent to a remote request
     */
    public long getTotalTime() {
        return totalTime;
    }

    private static final long serialVersionUID = 1L;
}
