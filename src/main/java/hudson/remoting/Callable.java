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
import java.io.Serializable;
import org.jenkinsci.remoting.ChannelStateException;
import org.jenkinsci.remoting.RoleSensitive;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

// TODO: Make it SerializableOnlyOverRemoting?
// Oleg Nenashev: Formally there is no reason for that, you can serialize object over whatever binary stream and then
// execute it on remote side if it has channel instance. Likely YAGNI, but I can imagine such Pipeline context class
// implementation.
/**
 * Represents computation to be done on a remote system.
 *
 * <p>You probably don't want to implement this directly in Jenkins, instead choose {@code MasterToSlaveCallable},
 * {@code NotReallyRoleSensitiveCallable}, or (in rare cases) {@code SlaveToMasterCallable}.</p>
 *
 * @see Channel
 * @author Kohsuke Kawaguchi
 */
public interface Callable<V, T extends Throwable> extends Serializable, RoleSensitive {
    /**
     * Performs computation and returns the result,
     * or throws some exception.
     */
    V call() throws T;

    /**
     * Gets a channel for the operation inside callable.
     * @return Channel Instance
     * @throws ChannelStateException Channel is not associated with the thread
     * @since 3.15
     */
    @NonNull
    default Channel getChannelOrFail() throws ChannelStateException {
        final Channel ch = Channel.current();
        if (ch == null) {
            // This logic does not prevent from improperly serializing objects within Remoting calls.
            // If it happens in API calls in external usages, we wish good luck with diagnosing Remoting issues
            // and leaks in ExportTable.
            // TODO: maybe there is a way to actually diagnose this case?
            final Thread t = Thread.currentThread();
            throw new ChannelStateException(
                    null,
                    "The calling thread " + t + " has no associated channel. "
                            + "The current object " + this + " is " + SerializableOnlyOverRemoting.class
                            + ", but it is likely being serialized/deserialized without the channel");
        }
        return ch;
    }

    /**
     * Gets an open channel, which is ready to accept commands.
     *
     * It is a convenience method for cases, when a callable needs to invoke call backs on the controller.
     * In such case the requests will be likely failed by {@linkplain UserRequest} logic anyway, but it is better to fail fast.
     *
     * @return Channel instance
     * @throws ChannelStateException The channel is closing down or has been closed.
     *          Also happens if the channel is not associated with the thread at all.
     * @since 3.15
     */
    @NonNull
    default Channel getOpenChannelOrFail() throws ChannelStateException {
        final Channel ch = getChannelOrFail();
        if (ch.isClosingOrClosed()) {
            throw new ChannelClosedException(
                    ch,
                    "The associated channel " + ch + " is closing down or has closed down",
                    ch.getCloseRequestCause());
        }
        return ch;
    }
}
