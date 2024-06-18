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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ByteBufferQueueOutputStreamTest {
    @Test
    public void writeAll() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";

        ByteBufferQueue queue = new ByteBufferQueue(10);
        try (ByteBufferQueueOutputStream instance = new ByteBufferQueueOutputStream(queue)) {
            instance.write(str.getBytes(StandardCharsets.UTF_8));
            assertThat(read(queue), is(str));
        }
    }

    @Test
    public void writeSome() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";

        ByteBufferQueue queue = new ByteBufferQueue(10);
        try (ByteBufferQueueOutputStream instance = new ByteBufferQueueOutputStream(queue)) {
            instance.write(str.getBytes(StandardCharsets.UTF_8), 0, 10);
            assertThat(read(queue), is("AbCdEfGhIj"));
        }
    }

    @Test
    public void writeBytes() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";

        ByteBufferQueue queue = new ByteBufferQueue(10);
        try (ByteBufferQueueOutputStream instance = new ByteBufferQueueOutputStream(queue)) {
            for (int i = 1; i < str.length(); i += 2) {
                instance.write(str.charAt(i));
            }
            assertThat(read(queue), is("bdfhjlnprtvxz"));
        }
    }

    private String read(ByteBufferQueue queue) {
        ByteBuffer buf = ByteBuffer.allocate((int) queue.remaining());
        queue.get(buf);
        buf.flip();
        StringBuilder r = new StringBuilder(buf.remaining());
        while (buf.hasRemaining()) {
            r.append((char) buf.get());
        }
        return r.toString();
    }
}
