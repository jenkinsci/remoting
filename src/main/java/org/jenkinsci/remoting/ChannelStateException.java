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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Channel;
import java.io.IOException;
import java.lang.ref.WeakReference;

/**
 * Indicates invalid state of the channel during the operation.
 *
 * Exception stores the channel reference is a {@link WeakReference}, so the information may be deallocated at any moment.
 * Former channel name can be retrieved via {@link #getChannelName()}.
 * The reference also will not be serialized.
 *
 * @author Oleg Nenashev
 * @since 3.15
 */
public class ChannelStateException extends IOException {

    @NonNull
    private final String channelName;

    @CheckForNull
    private final transient WeakReference<Channel> channelRef;

    public ChannelStateException(@CheckForNull Channel channel, @NonNull String message) {
        super(message);
        channelRef = channel != null ? new WeakReference<>(channel) : null;
        channelName = channel != null ? channel.getName() : "unknown";
    }

    public ChannelStateException(@CheckForNull Channel channel, String message, @CheckForNull Throwable cause) {
        super(message, cause);
        channelRef = channel != null ? new WeakReference<>(channel) : null;
        channelName = channel != null ? channel.getName() : "unknown";
    }

    @CheckForNull
    public WeakReference<Channel> getChannelRef() {
        return channelRef;
    }

    /**
     * Gets channel name.
     * @return Channel name ot {@code unknown} if it is not known.
     */
    @NonNull
    public String getChannelName() {
        return channelName;
    }

    /**
     * Gets channel associated with the exception.
     *
     * The channel reference is a {@link WeakReference}, so the information may be deallocated at any moment.
     * Former channel name can be retrieved via {@link #getChannelName()}.
     * The reference also will not be serialized.
     * @return Channel reference if it is available. {@code null} otherwise.
     */
    @CheckForNull
    public Channel getChannel() {
        return channelRef != null ? channelRef.get() : null;
    }

    @Override
    public String getMessage() {
        Channel ch = getChannel();
        String infoForMessage = ch != null ? ch.toString() : channelName;
        return String.format("Channel \"%s\": %s", infoForMessage, super.getMessage());
    }
}
