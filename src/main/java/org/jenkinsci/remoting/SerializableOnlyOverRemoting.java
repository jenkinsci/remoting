/*
 *
 * The MIT License
 *
 * Copyright (c) 2017 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 */

package org.jenkinsci.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Channel;
import java.io.NotSerializableException;
import java.io.Serializable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.ProtectedExternally;

/**
 * This interface indicates objects which are {@link Serializable} only for sending over the Remoting {@link Channel}.
 *
 * Usually it means that the object requires export of the class via {@link Channel}
 * and {@code hudson.remoting.ExportTable}.
 * Attempts to serialize the instance of this interface for different purposes lead to undefined behavior.
 *
 * @author Oleg Nenashev
 * @since 3.14
 */
public interface SerializableOnlyOverRemoting extends Serializable {

    /**
     * Gets current channel or fails with {@link NotSerializableException}.
     *
     * This method is designed for serialization/deserialization methods in the channel.
     * @return Current channel
     * @throws NotSerializableException the calling thread has no associated channel.
     *      In such case the object cannot be serialized.
     */
    @NonNull
    @Restricted(ProtectedExternally.class)
    default Channel getChannelForSerialization() throws NotSerializableException {
        final Channel ch = Channel.current();
        if (ch == null) {
            // This logic does not prevent from improperly serializing objects within Remoting calls.
            // If it happens in API calls in external usages, we wish good luck with diagnosing Remoting issues
            // and leaks in ExportTable.
            // TODO: maybe there is a way to actually diagnose this case?
            final Thread t = Thread.currentThread();
            throw new NotSerializableException("The calling thread " + t + " has no associated channel. "
                    + "The current object " + this + " is " + SerializableOnlyOverRemoting.class
                    + ", but it is likely being serialized/deserialized without the channel");
        }
        return ch;
    }
}
