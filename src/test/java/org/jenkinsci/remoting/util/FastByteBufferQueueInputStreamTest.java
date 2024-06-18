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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class FastByteBufferQueueInputStreamTest {
    @Test
    public void readAll() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";

        ByteBufferQueue queue = new ByteBufferQueue(10);
        queue.put(ByteBuffer.wrap(str.getBytes(StandardCharsets.UTF_8)));
        FastByteBufferQueueInputStream instance = new FastByteBufferQueueInputStream(queue, 26);

        assertThat(read(instance), is(str));
    }

    @Test
    public void readLimit() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";

        ByteBufferQueue queue = new ByteBufferQueue(10);
        queue.put(ByteBuffer.wrap(str.getBytes(StandardCharsets.UTF_8)));
        FastByteBufferQueueInputStream instance = new FastByteBufferQueueInputStream(queue, 10);

        assertThat(read(instance, 10), is("AbCdEfGhIj"));
    }

    @Test
    public void readSome() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";

        ByteBufferQueue queue = new ByteBufferQueue(10);
        queue.put(ByteBuffer.wrap(str.getBytes(StandardCharsets.UTF_8)));
        FastByteBufferQueueInputStream instance = new FastByteBufferQueueInputStream(queue, 26);

        assertThat(read(instance, 10), is("AbCdEfGhIj"));
    }

    @Test
    public void readBytes() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";

        ByteBufferQueue queue = new ByteBufferQueue(10);
        queue.put(ByteBuffer.wrap(str.getBytes(StandardCharsets.UTF_8)));
        FastByteBufferQueueInputStream instance = new FastByteBufferQueueInputStream(queue, 26);

        byte[] bytes = new byte[10];
        assertThat(instance.read(bytes), is(10));
        assertThat(new String(bytes, StandardCharsets.UTF_8), is("AbCdEfGhIj"));
        assertThat(instance.read(bytes), is(10));
        assertThat(new String(bytes, StandardCharsets.UTF_8), is("KlMnOpQrSt"));
        assertThat(instance.read(bytes), is(6));
        assertThat(new String(bytes, StandardCharsets.UTF_8), is("UvWxYzQrSt"));
        instance.close();
    }

    @Test
    public void readBytesOffset() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";

        ByteBufferQueue queue = new ByteBufferQueue(10);
        queue.put(ByteBuffer.wrap(str.getBytes(StandardCharsets.UTF_8)));
        FastByteBufferQueueInputStream instance = new FastByteBufferQueueInputStream(queue, 26);

        byte[] bytes = new byte[10];
        assertThat(instance.read(bytes, 5, 3), is(3));
        assertThat(new String(bytes, StandardCharsets.UTF_8), is("\u0000\u0000\u0000\u0000\u0000AbC\u0000\u0000"));
        assertThat(instance.read(bytes, 0, 2), is(2));
        assertThat(new String(bytes, StandardCharsets.UTF_8), is("dE\u0000\u0000\u0000AbC\u0000\u0000"));
        assertThat(instance.read(bytes, 2, 8), is(8));
        assertThat(new String(bytes, StandardCharsets.UTF_8), is("dEfGhIjKlM"));
        assertThat(instance.read(bytes, 2, 8), is(8));
        assertThat(new String(bytes, StandardCharsets.UTF_8), is("dEnOpQrStU"));
        assertThat(instance.read(bytes, 2, 8), is(5));
        assertThat(new String(bytes, StandardCharsets.UTF_8), is("dEvWxYzStU"));
        instance.close();
    }

    @Test
    public void skipRead() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";

        ByteBufferQueue queue = new ByteBufferQueue(10);
        queue.put(ByteBuffer.wrap(str.getBytes(StandardCharsets.UTF_8)));
        try (FastByteBufferQueueInputStream instance = new FastByteBufferQueueInputStream(queue, 26)) {
            StringBuilder buf = new StringBuilder();
            int b;
            do {
                if (instance.skip(1) != 1) {
                    b = -1;
                } else {
                    b = instance.read();
                    if (b != -1) {
                        buf.append((char) b);
                    }
                }
            } while (b != -1);

            assertThat(buf.toString(), is("bdfhjlnprtvxz"));
        }
    }

    @Test
    public void markRead() throws Exception {
        String str = "AbCdEfGhIjKlMnOpQrStUvWxYz";

        ByteBufferQueue queue = new ByteBufferQueue(10);
        queue.put(ByteBuffer.wrap(str.getBytes(StandardCharsets.UTF_8)));
        FastByteBufferQueueInputStream instance = new FastByteBufferQueueInputStream(queue, 26);
        assertThat(instance.markSupported(), is(false));
        instance.close();
    }

    private static String read(InputStream is) throws IOException {
        ByteArrayOutputStream tmp = new ByteArrayOutputStream();
        int b;
        while (-1 != (b = is.read())) {
            tmp.write(b);
        }
        return tmp.toString(StandardCharsets.UTF_8);
    }

    private static String read(InputStream is, int count) throws IOException {
        ByteArrayOutputStream tmp = new ByteArrayOutputStream();
        int b;
        while (count > 0 && -1 != (b = is.read())) {
            tmp.write(b);
            count--;
        }
        return tmp.toString(StandardCharsets.UTF_8);
    }
}
