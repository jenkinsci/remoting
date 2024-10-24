/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, InfraDNA, Inc.
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

import java.util.Objects;

/**
 * Hides the logic of starting/stopping a channel for test,
 * and where/how the other side is running.
 *
 * @author Kohsuke Kawaguchi
 */
public interface ChannelRunner {
    /**
     * Launches a pair of channel and returns this side of it.
     */
    Channel start() throws Exception;

    /**
     * Terminates the channel started by {@link #start()} and cleans up
     * any remaining resources.
     */
    void stop(Channel channel) throws Exception;

    /**
     * Human readable name for this channel runner. Used to annotate test reports.
     */
    String getName();

    default <T extends Exception> void withChannel(ConsumerThrowable<Channel, T> f) throws Exception {
        Channel channel = start();
        try {
            f.accept(channel);
        } finally {
            stop(channel);
        }
    }

    @FunctionalInterface
    interface ConsumerThrowable<C, T extends Throwable> {
        void accept(C c) throws T;
        /**
         * Returns a composed {@code Consumer} that performs, in sequence, this
         * operation followed by the {@code after} operation. If performing either
         * operation throws an exception, it is relayed to the caller of the
         * composed operation.  If performing this operation throws an exception,
         * the {@code after} operation will not be performed.
         *
         * @param after the operation to perform after this operation
         * @return a composed {@code Consumer} that performs in sequence this
         * operation followed by the {@code after} operation
         * @throws NullPointerException if {@code after} is null
         */
        default ConsumerThrowable<C, T> andThen(ConsumerThrowable<C, T> after) throws T {
            Objects.requireNonNull(after);
            return (C c) -> {
                accept(c);
                after.accept(c);
            };
        }
    }
}
