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
import org.junit.Test;

public class ByteBufferQueueTest {

    @Test
    public void newInstanceIsEmpty() {
        assertThat(new ByteBufferQueue(100).hasRemaining(), is(false));
    }

    @Test
    public void putEmptyBufferAndStillEmpty() {
        ByteBufferQueue queue = new ByteBufferQueue(100);
        ByteBuffer src = ByteBuffer.allocate(1);
        src.flip();
        queue.put(src);
        assertThat(queue.hasRemaining(), is(false));
    }

    @Test
    public void putOneByteAndHasRemaining() {
        ByteBufferQueue queue = new ByteBufferQueue(100);
        ByteBuffer src = ByteBuffer.allocate(1);
        src.put((byte) 0);
        src.flip();
        queue.put(src);
        assertThat(queue.hasRemaining(), is(true));
    }

    @Test
    public void putGetByteAndHasRemaining() {
        ByteBufferQueue queue = new ByteBufferQueue(100);
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte b = (byte) i;
            queue.put(b);
            assertThat(queue.hasRemaining(), is(true));
            assertThat(queue.get(), is(b));
            assertThat(queue.hasRemaining(), is(false));
        }
    }

    @Test
    public void putOneByteGetSequences() {
        ByteBufferQueue queue = new ByteBufferQueue(100);
        ByteBuffer src = ByteBuffer.allocate(1);
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte b = (byte) i;
            src.clear();
            src.put(b);
            src.flip();
            queue.put(src);
            assertThat(queue.hasRemaining(), is(true));
        }
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte b = (byte) i;
            assertThat(queue.hasRemaining(), is(true));
            assertThat(queue.get(), is(b));
        }
        assertThat(queue.hasRemaining(), is(false));
    }

    @Test
    public void putGetOneByteSequences() {
        ByteBufferQueue queue = new ByteBufferQueue(100);
        ByteBuffer src = ByteBuffer.allocate(1);
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte b = (byte) i;
            queue.put(b);
            assertThat(queue.hasRemaining(), is(true));
        }
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte b = (byte) i;
            assertThat(queue.hasRemaining(), is(true));
            src.clear();
            queue.get(src);
            src.flip();
            assertThat(src.get(), is(b));
        }
        assertThat(queue.hasRemaining(), is(false));
    }

    @Test
    public void putGetByteSequences() {
        ByteBufferQueue queue = new ByteBufferQueue(100);
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte b = (byte) i;
            queue.put(b);
            assertThat(queue.hasRemaining(), is(true));
        }
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte b = (byte) i;
            assertThat(queue.hasRemaining(), is(true));
            assertThat(queue.get(), is(b));
        }
        assertThat(queue.hasRemaining(), is(false));
    }

    @Test
    public void putGetByteArraySequences() {
        ByteBufferQueue queue = new ByteBufferQueue(100);
        byte[] dst = new byte[1];
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte b = (byte) i;
            queue.put(b);
            assertThat(queue.hasRemaining(), is(true));
        }
        for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++) {
            byte b = (byte) i;
            assertThat(queue.hasRemaining(), is(true));
            assertThat(queue.get(dst, 0, 1), is(1));
            assertThat(dst[0], is(b));
        }
        assertThat(queue.hasRemaining(), is(false));
    }

    @Test
    public void putGetDrainsQueue() {
        ByteBufferQueue queue = new ByteBufferQueue(100);
        ByteBuffer src = ByteBuffer.allocate(1);
        src.put((byte) 0);
        src.flip();
        queue.put(src);
        queue.get(ByteBuffer.allocate(2));
        assertThat(queue.hasRemaining(), is(false));
    }

    @Test
    public void putGetFillsDestLeavingRemaining() {
        ByteBufferQueue queue = new ByteBufferQueue(100);
        ByteBuffer src = ByteBuffer.allocate(4);
        src.put((byte) 0);
        src.put((byte) 2);
        src.put((byte) 4);
        src.put((byte) 8);
        src.flip();
        queue.put(src);
        ByteBuffer dst = ByteBuffer.allocate(2);
        queue.get(dst);
        assertThat(queue.hasRemaining(), is(true));
        dst.flip();
        assertThat(dst.get(), is((byte) 0));
        assertThat(dst.get(), is((byte) 2));
        dst.clear();
        queue.get(dst);
        assertThat(queue.hasRemaining(), is(false));
        dst.flip();
        assertThat(dst.get(), is((byte) 4));
        assertThat(dst.get(), is((byte) 8));
    }

    @Test
    public void putPutGetUngetPutGetGetGet() {
        ByteBufferQueue queue = new ByteBufferQueue(2);
        ByteBuffer src = ByteBuffer.allocate(4);
        src.put((byte) 0);
        src.put((byte) 2);
        src.put((byte) 4);
        src.put((byte) 8);
        src.flip();
        queue.put(src);
        src.clear();
        src.put((byte) 10);
        src.put((byte) 12);
        src.put((byte) 14);
        src.put((byte) 18);
        src.flip();
        queue.put(src);
        ByteBuffer dst = ByteBuffer.allocate(2);
        queue.get(dst);
        assertThat(queue.hasRemaining(), is(true));
        dst.flip();
        dst.mark();
        assertThat(dst.get(), is((byte) 0));
        assertThat(dst.get(), is((byte) 2));
        dst.reset();
        queue.unget(dst);
        src.clear();
        src.put((byte) 20);
        src.put((byte) 22);
        src.put((byte) 24);
        src.put((byte) 28);
        src.flip();
        queue.put(src);
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 0));
        assertThat(dst.get(), is((byte) 2));
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 4));
        assertThat(dst.get(), is((byte) 8));
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 10));
        assertThat(dst.get(), is((byte) 12));
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 14));
        assertThat(dst.get(), is((byte) 18));
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 20));
        assertThat(dst.get(), is((byte) 22));
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 24));
        assertThat(dst.get(), is((byte) 28));
    }

    @Test
    public void putPutGetBigUngetBigPutGetGetGet() {
        ByteBufferQueue queue = new ByteBufferQueue(2);
        ByteBuffer src = ByteBuffer.allocate(4);
        src.put((byte) 0);
        src.put((byte) 2);
        src.put((byte) 4);
        src.put((byte) 8);
        src.flip();
        queue.put(src);
        src.clear();
        src.put((byte) 10);
        src.put((byte) 12);
        src.put((byte) 14);
        src.put((byte) 18);
        src.flip();
        queue.put(src);
        ByteBuffer dst = ByteBuffer.allocate(6);
        queue.get(dst);
        assertThat(queue.hasRemaining(), is(true));
        dst.flip();
        dst.mark();
        assertThat(dst.get(), is((byte) 0));
        assertThat(dst.get(), is((byte) 2));
        assertThat(dst.get(), is((byte) 4));
        assertThat(dst.get(), is((byte) 8));
        assertThat(dst.get(), is((byte) 10));
        assertThat(dst.get(), is((byte) 12));
        dst.reset();
        queue.unget(dst);
        src.clear();
        src.put((byte) 20);
        src.put((byte) 22);
        src.put((byte) 24);
        src.put((byte) 28);
        src.flip();
        queue.put(src);
        dst = ByteBuffer.allocate(2);
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 0));
        assertThat(dst.get(), is((byte) 2));
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 4));
        assertThat(dst.get(), is((byte) 8));
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 10));
        assertThat(dst.get(), is((byte) 12));
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 14));
        assertThat(dst.get(), is((byte) 18));
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 20));
        assertThat(dst.get(), is((byte) 22));
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 24));
        assertThat(dst.get(), is((byte) 28));
    }

    @Test
    public void putPutGetBigUngetBiggerPutGetGetGet() {
        ByteBufferQueue queue = new ByteBufferQueue(2);
        ByteBuffer src = ByteBuffer.allocate(4);
        src.put((byte) 0);
        src.put((byte) 2);
        src.put((byte) 4);
        src.put((byte) 8);
        src.flip();
        queue.put(src);
        src.clear();
        src.put((byte) 10);
        src.put((byte) 12);
        src.put((byte) 14);
        src.put((byte) 18);
        src.flip();
        queue.put(src);
        ByteBuffer dst = ByteBuffer.allocate(6);
        queue.get(dst);
        assertThat(queue.hasRemaining(), is(true));
        dst.flip();
        dst.mark();
        assertThat(dst.get(), is((byte) 0));
        assertThat(dst.get(), is((byte) 2));
        assertThat(dst.get(), is((byte) 4));
        assertThat(dst.get(), is((byte) 8));
        assertThat(dst.get(), is((byte) 10));
        assertThat(dst.get(), is((byte) 12));
        dst = ByteBuffer.allocate(10);
        dst.put((byte) 30);
        dst.put((byte) 31);
        dst.put((byte) 32);
        dst.put((byte) 33);
        dst.put((byte) 34);
        dst.put((byte) 35);
        dst.put((byte) 36);
        dst.put((byte) 37);
        dst.put((byte) 38);
        dst.put((byte) 39);
        dst.flip();
        queue.unget(dst);
        src.clear();
        src.put((byte) 20);
        src.put((byte) 22);
        src.put((byte) 24);
        src.put((byte) 28);
        src.flip();
        queue.put(src);
        dst = ByteBuffer.allocate(8);
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 30));
        assertThat(dst.get(), is((byte) 31));
        assertThat(dst.get(), is((byte) 32));
        assertThat(dst.get(), is((byte) 33));
        assertThat(dst.get(), is((byte) 34));
        assertThat(dst.get(), is((byte) 35));
        assertThat(dst.get(), is((byte) 36));
        assertThat(dst.get(), is((byte) 37));
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.get(), is((byte) 38));
        assertThat(dst.get(), is((byte) 39));
        assertThat(dst.get(), is((byte) 14));
        assertThat(dst.get(), is((byte) 18));
        assertThat(dst.get(), is((byte) 20));
        assertThat(dst.get(), is((byte) 22));
        assertThat(dst.get(), is((byte) 24));
        assertThat(dst.get(), is((byte) 28));
    }

    @Test
    public void internalBufferOverflow_8_48_12() {
        internalBufferOverflow(8, 48, 12);
    }

    @Test
    public void internalBufferOverflow_7_47_11() {
        internalBufferOverflow(7, 47, 11);
    }

    @Test
    public void internalBufferOverflow_13_1123_7() {
        internalBufferOverflow(7, 47, 11);
    }

    @Test
    public void internalBufferOverflow_13_3391_23() {
        internalBufferOverflow(7, 47, 11);
    }

    public void internalBufferOverflow(int intSize, int srcSize, int dstSize) {
        ByteBufferQueue queue = new ByteBufferQueue(intSize);
        ByteBuffer src = ByteBuffer.allocate(srcSize);
        for (byte v = 0; src.hasRemaining(); v++) {
            src.put(v);
        }
        assertThat("No more room to write", src.hasRemaining(), is(false));
        src.flip();
        assertThat("We have data to read", src.hasRemaining(), is(true));
        assertThat("The queue has no data", queue.hasRemaining(), is(false));
        queue.put(src);
        assertThat("No more data to read", src.hasRemaining(), is(false));
        assertThat("The queue has data", queue.hasRemaining(), is(true));
        ByteBuffer dst = ByteBuffer.allocate(dstSize);
        byte v = 0;
        while (queue.hasRemaining()) {
            dst.clear();
            queue.get(dst);
            dst.flip();
            while (dst.hasRemaining()) {
                assertThat(dst.get(), is(v++));
            }
        }
    }

    @Test
    public void interleavedReadWrite_8_12_4_16_1() {
        interleavedReadWrite(8, 12, 4, 16, 1);
    }

    @Test
    public void interleavedReadWrite_8_16_4_12_1() {
        interleavedReadWrite(8, 16, 4, 12, 1);
    }

    @Test
    public void interleavedReadWrite_7_11_13_17_3() {
        interleavedReadWrite(7, 11, 13, 17, 3);
    }

    public void interleavedReadWrite(int intSize, int srcSize, int srcCount, int dstSize, int dstCount) {
        ByteBufferQueue queue = new ByteBufferQueue(intSize);
        ByteBuffer src = ByteBuffer.allocate(srcSize);
        ByteBuffer dst = ByteBuffer.allocate(dstSize);
        byte srcV = 0;
        byte dstV = 0;
        assertThat("The queue has no data", queue.hasRemaining(), is(false));
        while (srcCount-- > 0) {
            src.clear();
            for (; src.hasRemaining(); srcV++) {
                src.put(srcV);
            }
            assertThat("No more room to write", src.hasRemaining(), is(false));
            src.flip();
            assertThat("We have data to read", src.hasRemaining(), is(true));
            queue.put(src);
            assertThat("No more data to read", src.hasRemaining(), is(false));
            assertThat("The queue has data", queue.hasRemaining(), is(true));
            for (int i = 0; i < dstCount; i++) {
                dst.clear();
                queue.get(dst);
                dst.flip();
                while (dst.hasRemaining()) {
                    assertThat(dst.get(), is(dstV++));
                }
            }
        }
        while (queue.hasRemaining()) {
            dst.clear();
            queue.get(dst);
            dst.flip();
            while (dst.hasRemaining()) {
                assertThat(dst.get(), is(dstV++));
            }
        }
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.hasRemaining(), is(false));
    }

    @Test
    public void interleavingReadWrite_7_11_13_853_19_5() {
        interleavingReadWrite(7, 11, 13, 853, 19, 5);
    }

    @Test
    public void interleavingReadWrite_7_11_13_853_5_19() {
        interleavingReadWrite(7, 11, 13, 853, 19, 5);
    }

    @Test
    public void interleavingReadWrite_23_37_53_1051_13_29() {
        interleavingReadWrite(23, 37, 53, 1051, 13, 29);
    }

    public void interleavingReadWrite(
            int intSize, int srcSize, int dstSize, int srcCount, int srcModulus, int dstModulus) {
        ByteBufferQueue queue = new ByteBufferQueue(intSize);
        ByteBuffer src = ByteBuffer.allocate(srcSize);
        ByteBuffer dst = ByteBuffer.allocate(dstSize);
        byte srcV = 0;
        byte dstV = 0;
        assertThat("The queue has no data", queue.hasRemaining(), is(false));
        int index = 0;
        while (srcCount > 0) {
            if (index % srcModulus == 0) {
                srcCount--;
                src.clear();
                for (; src.hasRemaining(); srcV++) {
                    src.put(srcV);
                }
                assertThat("No more room to write", src.hasRemaining(), is(false));
                src.flip();
                assertThat("We have data to read", src.hasRemaining(), is(true));
                queue.put(src);
                assertThat("No more data to read", src.hasRemaining(), is(false));
                assertThat("The queue has data", queue.hasRemaining(), is(true));
            }
            if (index % dstModulus == 0) {
                dst.clear();
                queue.get(dst);
                dst.flip();
                while (dst.hasRemaining()) {
                    assertThat(dst.get(), is(dstV++));
                }
            }
        }
        while (queue.hasRemaining()) {
            dst.clear();
            queue.get(dst);
            dst.flip();
            while (dst.hasRemaining()) {
                assertThat(dst.get(), is(dstV++));
            }
        }
        dst.clear();
        queue.get(dst);
        dst.flip();
        assertThat(dst.hasRemaining(), is(false));
    }
}
