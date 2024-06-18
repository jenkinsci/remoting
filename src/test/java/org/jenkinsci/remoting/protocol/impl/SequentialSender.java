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
package org.jenkinsci.remoting.protocol.impl;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.jenkinsci.remoting.protocol.IOBufferMatcher;

public class SequentialSender implements Callable<Void> {
    private static final Logger LOGGER = Logger.getLogger(SequentialSender.class.getName());
    private final IOBufferMatcher app;
    private final int limit;
    private final int modulo;

    public SequentialSender(IOBufferMatcher app, int limit, int modulo) {
        this.app = app;
        this.limit = limit;
        this.modulo = modulo;
    }

    public static String toIntArray(byte[] bytes) {
        StringBuilder buf = new StringBuilder("[");
        IntBuffer b = ByteBuffer.wrap(bytes).asIntBuffer();
        boolean first = true;
        boolean any = false;
        int start = 0;
        int expect = 0;
        while (b.hasRemaining()) {
            if (first) {
                start = b.get();
                expect = start + 1;
                first = false;
            } else {
                int cur = b.get();
                if (cur == expect) {
                    expect = expect + 1;
                } else {
                    if (any) {
                        buf.append(", ");
                    }
                    any = true;
                    buf.append(start);
                    if (start == expect - 2) {
                        buf.append(", ");
                        buf.append(expect - 1);
                    } else if (start < expect - 1) {
                        buf.append(" - ");
                        buf.append(expect - 1);
                    }
                    start = cur;
                    expect = start + 1;
                }
            }
        }
        if (!first) {
            if (any) {
                buf.append(", ");
            }
            buf.append(start);
            if (start == expect - 2) {
                buf.append(", ");
                buf.append(expect - 1);
            } else if (start < expect - 1) {
                buf.append(" - ");
                buf.append(expect - 1);
            }
        }
        buf.append("]");
        return buf.toString();
    }

    public static Matcher<byte[]> matcher(int limit) {
        return new SequentialMatcher(limit);
    }

    @Override
    public Void call() throws Exception {
        int count = 0;
        int n = 0;
        long nextLog = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ByteBuffer buf = ByteBuffer.allocate(512);
        while (count < limit && !Thread.interrupted()) {
            buf.clear();
            IntBuffer ints = buf.asIntBuffer();
            while (ints.hasRemaining() && count < limit) {
                ints.put(count++);
            }
            buf.limit(ints.position() * 4);
            while (buf.hasRemaining()) {
                app.send(buf);
            }
            n++;
            if (n % modulo == 0) {
                Thread.yield();
            }
            if (nextLog - System.nanoTime() < 0) {
                nextLog = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                LOGGER.log(Level.INFO, "Sent {0} in {1} blocks", new Object[] {count, n});
            }
        }
        LOGGER.log(Level.INFO, "Done {0} in {1} blocks", new Object[] {limit, n});
        return null;
    }

    private static class SequentialMatcher extends BaseMatcher<byte[]> {
        private final int limit;

        private SequentialMatcher(int limit) {
            this.limit = limit;
        }

        @Override
        public boolean matches(Object item) {
            if (item instanceof byte[]) {
                if (((byte[]) item).length != limit * 4) {
                    return false;
                }
                IntBuffer intBuffer = ByteBuffer.wrap((byte[]) item).asIntBuffer();
                int pos = 0;
                while (intBuffer.hasRemaining()) {
                    if (intBuffer.get() != pos) {
                        return false;
                    }
                    pos++;
                }
                return pos == limit;
            }
            return false;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("A sequence of integers from 0 to ").appendValue(limit - 1);
        }
    }
}
