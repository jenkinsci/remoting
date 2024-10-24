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

import java.nio.Buffer;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Utility methods to help working with {@link ByteBuffer}s.
 *
 * @since 3.0
 */
public final class ByteBufferUtils {

    /**
     * A handy constant to use for no-op send/receive calls.
     */
    public static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /**
     * This is a utility class, prevent accidental instance creation.
     */
    private ByteBufferUtils() {
        throw new IllegalAccessError("Utility class");
    }

    /**
     * Transfer bytes from src to dst. If the source has more bytes than the destination then only as many bytes
     * as the destination has capacity to take will be transferred.
     *
     * @param src the source.
     * @param dst the destination.
     */
    public static void put(ByteBuffer src, ByteBuffer dst) {
        if (src.remaining() > dst.remaining()) {
            int limit = src.limit();
            try {
                ((Buffer) src).limit(src.position() + dst.remaining());
                dst.put(src);
            } finally {
                ((Buffer) src).limit(limit);
            }
        } else {
            dst.put(src);
        }
    }

    /**
     * Transfer a string into the destination buffer as a UTF-8 encoded string prefixed by a two byte length.
     *
     * @param src the string to encode and copy.
     * @param dst the destination.
     */
    public static void putUTF8(String src, ByteBuffer dst) {
        byte[] bytes = src.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IllegalArgumentException("UTF-8 encoded string must be less than 65536 bytes");
        }
        if (dst.remaining() < 2 + bytes.length) {
            throw new BufferOverflowException();
        }
        dst.asShortBuffer().put((short) bytes.length);
        ((Buffer) dst).position(dst.position() + 2);
        dst.put(bytes);
    }

    /**
     * Read from the source buffer a string that has been encoded in UTF-8 and prefixed by a two byte length.
     *
     * @param src the source buffer.
     * @return the string.
     * @throws BufferUnderflowException if the source buffer has not got the full string.
     */
    public static String getUTF8(ByteBuffer src) {
        if (src.remaining() < 2) {
            throw new BufferUnderflowException();
        }
        // we peek the length so that we either read it all or none, do not leave partially consumed data in buffer
        int length = src.asShortBuffer().get() & 0xffff;
        if (src.remaining() < length + 2) {
            throw new BufferUnderflowException();
        }
        ((Buffer) src).position(src.position() + 2);
        byte[] bytes = new byte[length];
        src.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Wrap a string encoded as a UTF-8 string prefixed by a two byte length.
     *
     * @param string the string.
     * @return a {@link ByteBuffer} containing the two byte length followed by the string encoded as UTF-8.
     */
    public static ByteBuffer wrapUTF8(String string) {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new IllegalArgumentException("UTF-8 encoded string must be less than 65536 bytes");
        }
        ByteBuffer result = ByteBuffer.allocate(bytes.length + 2);
        result.asShortBuffer().put((short) bytes.length);
        ((Buffer) result).position(result.position() + 2);
        result.put(bytes);
        ((Buffer) result).flip();
        return result;
    }

    /**
     * Accumulate the given buffer into the current context. Allocation is performed only if needed.
     *
     * @param src the buffer to accumulate
     * @param dst the buffer to accumulate into
     * @return the accumulated buffer (may be {@code dst} or a new buffer if {@code dst} did not have sufficient
     * capacity)
     */
    public static ByteBuffer accumulate(ByteBuffer src, ByteBuffer dst) {
        if (dst.capacity() - dst.remaining() > src.remaining()) {
            int oldPosition = dst.position();
            ((Buffer) dst).position(dst.limit());
            ((Buffer) dst).limit(dst.limit() + src.remaining());
            dst.put(src);
            ((Buffer) dst).position(oldPosition);
            return dst;
        } else {
            ByteBuffer newDst = ByteBuffer.allocate((dst.remaining() + src.remaining()) * 2);
            newDst.put(dst);
            newDst.put(src);
            ((Buffer) newDst).flip();
            return newDst;
        }
    }

    /**
     * Duplicate a byte buffer for storing for future use.
     *
     * @param buffer the buffer to duplicate
     * @return the newly allocated buffer
     */
    public static ByteBuffer duplicate(ByteBuffer buffer) {
        ByteBuffer newBuffer = ByteBuffer.allocate(buffer.remaining() * 2);
        newBuffer.put(buffer);
        ((Buffer) newBuffer).flip();
        return newBuffer;
    }
}
