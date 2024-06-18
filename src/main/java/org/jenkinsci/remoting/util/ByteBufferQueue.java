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
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.jcip.annotations.NotThreadSafe;

/**
 * A helper class to make queuing up of data easier.
 *
 * @since 3.0
 */
@NotThreadSafe
public class ByteBufferQueue {
    /**
     * The initial size of the {@link #buffers} array.
     */
    private static final int INITIAL_CAPACITY = 16;
    /**
     * The size above which to consider shrinking the {@link #buffers} array after compaction.
     */
    private static final int SHRINK_CAPACITY = 512;
    /**
     * If, after this number of times where the shrink condition has been met without needing to grow the backing array,
     * we will shrink the backing array.
     */
    private static final int SHRINK_THRESHOLD = 8;
    /**
     * An empty byte array constant.
     */
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    /**
     * The array of buffers.
     */
    private ByteBuffer[] buffers;
    /**
     * The size of the individual {@link ByteBuffer} allocations.
     */
    private final int bufferSize;
    /**
     * Our current read index.
     */
    private int readIndex;
    /**
     * Our current read position.
     */
    private int readPosition;
    /**
     * Our current write index.
     */
    private int writeIndex;
    /**
     * The number of times we could have shrunk.
     */
    private int shrinkCount;

    /**
     * Constructor.
     *
     * @param bufferSize the size of buffers to use for queuing.
     */
    public ByteBufferQueue(int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException();
        }
        this.buffers = new ByteBuffer[INITIAL_CAPACITY];
        this.bufferSize = bufferSize;
        this.readIndex = 0;
        this.readPosition = 0;
        this.writeIndex = 0;
        this.buffers[writeIndex] = ByteBuffer.allocate(bufferSize);
    }

    /**
     * Compact the {@link #buffers} array so that the readIndex is {@literal 0}.
     */
    private void compact() {
        // skip over any empty buffers
        while (readIndex < writeIndex && buffers[readIndex].position() == readPosition) {
            readIndex++;
            readPosition = 0;
        }
        if (readIndex > 0) {
            // compact the array start
            System.arraycopy(buffers, readIndex, buffers, 0, writeIndex - readIndex + 1);
            writeIndex -= readIndex;
            readIndex = 0;
            Arrays.fill(buffers, writeIndex + 1, buffers.length, null);
        }
    }

    /**
     * Add a new write buffer.
     */
    private void addWriteBuffer() {
        if (writeIndex + 1 >= buffers.length) {
            compact();
            if (writeIndex + 1 >= buffers.length) {
                // we need to grow the backing array
                buffers = Arrays.copyOf(buffers, buffers.length * 2);
                shrinkCount = 0;
            } else if (buffers.length >= SHRINK_CAPACITY && writeIndex + 1 < buffers.length / 4) {
                // after compaction, writeIndex is less than 1/4 of the way through the backing array
                // if we halve the backing array size, we still have room
                shrinkCount++;
                if (shrinkCount > SHRINK_THRESHOLD) {
                    buffers = Arrays.copyOf(buffers, buffers.length / 2);
                    shrinkCount = 0;
                }
            } else if (writeIndex + 1 >= buffers.length * 3 / 4) {
                // after compaction, writeIndex is more than 3/4 of the way through the backing array
                // reset the shrinkCount as we are using a sizeable amount of the backing array.
                shrinkCount = 0;
            }
        }
        writeIndex++;
        if (buffers[writeIndex] == null) {
            buffers[writeIndex] = newByteBuffer();
        } else {
            ((Buffer) buffers[writeIndex]).clear();
        }
    }

    /**
     * Creates a new byte buffer matched to the queue's current buffer size.
     *
     * @return a new byte buffer matched to the queue's current buffer size.
     */
    public ByteBuffer newByteBuffer() {
        return ByteBuffer.allocate(bufferSize);
    }

    /**
     * This method transfers the bytes remaining in the given source
     * buffer appended onto this buffer queue.
     *
     * @param src The source buffer from which bytes are to be read.
     */
    public void put(ByteBuffer src) {
        while (src.hasRemaining()) {
            while (!buffers[writeIndex].hasRemaining()) {
                addWriteBuffer();
            }
            if (src.remaining() > buffers[writeIndex].remaining()) {
                int limit = src.limit();
                ((Buffer) src).limit(src.position() + buffers[writeIndex].remaining());
                buffers[writeIndex].put(src);
                ((Buffer) src).limit(limit);
            } else {
                buffers[writeIndex].put(src);
            }
        }
    }

    /**
     * This method appends bytes from the byte array onto this buffer queue.
     *
     * @param src    the source byte array.
     * @param offset the offset from which to start taking bytes.
     * @param len    the number of bytes to transfer.
     */
    public void put(byte[] src, int offset, int len) {
        while (len > 0) {
            while (!buffers[writeIndex].hasRemaining()) {
                addWriteBuffer();
            }
            int remaining = buffers[writeIndex].remaining();
            if (len > remaining) {
                buffers[writeIndex].put(src, offset, remaining);
                offset += remaining;
                len -= remaining;
            } else {
                buffers[writeIndex].put(src, offset, len);
                return;
            }
        }
    }

    /**
     * This method appends a single byte onto this buffer queue.
     *
     * @param b the byte.
     */
    public void put(byte b) {
        while (!buffers[writeIndex].hasRemaining()) {
            addWriteBuffer();
        }
        buffers[writeIndex].put(b);
    }

    /**
     * Tells whether there are any bytes between the current read index and
     * the write index.
     *
     * @return {@code true} if, and only if, there is at least one byte
     * remaining in this buffer queue.
     */
    public boolean hasRemaining() {
        // first buffer is special
        if (buffers[readIndex].position() > readPosition) {
            return true;
        }
        for (int i = readIndex + 1; i <= writeIndex; i++) {
            if (buffers[i].position() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether there are any bytes between the current read index and
     * the write index.
     *
     * @param len the number of bytes that we want at least remaining
     * @return {@code true} if, and only if, there is at least {@literal len} bytes
     * remaining in this buffer queue.
     */
    public boolean hasRemaining(int len) {
        len -= buffers[readIndex].position() - readPosition;
        for (int i = readIndex + 1; i <= writeIndex; i++) {
            len -= buffers[i].position();
            if (len <= 0) {
                return true;
            }
        }
        return len <= 0;
    }

    /**
     * Returns how much data is remaining between the current read index and the write index.
     *
     * @return the total number of bytes remaining in this buffer queue.
     */
    public long remaining() {
        long total = buffers[readIndex].position() - readPosition;
        for (int i = readIndex + 1; i <= writeIndex; i++) {
            total += buffers[i].position();
        }
        return total;
    }

    /**
     * Returns how much data is remaining between the current read index and the write index.
     *
     * @param limit the maximum number of remaining bytes at which to short-circuit.
     * @return the total number of bytes remaining in this buffer queue or the supplied maximum if there is at least
     * the supplied limit remaining.
     */
    public int remaining(int limit) {
        int total = buffers[readIndex].position() - readPosition;
        if (total >= limit) {
            return limit;
        }
        for (int i = readIndex + 1; i <= writeIndex; i++) {
            total += buffers[i].position();
            if (total >= limit) {
                return limit;
            }
        }
        return total;
    }

    /**
     * Discards up to the specified number of bytes from the read index.
     * @param bytes the total number of bytes to discard.
     * @return the number of bytes actually discarded.
     */
    public long skip(long bytes) {
        long skipped = 0;
        while (bytes > 0) {
            if (readIndex >= writeIndex && buffers[readIndex].position() == readPosition) {
                if (writeIndex > 0) {
                    // this is a cheap compact
                    buffers[0] = buffers[writeIndex];
                    buffers[writeIndex] = null;
                    readIndex = writeIndex = 0;
                    ((Buffer) buffers[0]).clear();
                    readPosition = 0;
                }
                break;
            }
            int remaining = buffers[readIndex].position() - readPosition;
            if (remaining > bytes) {
                readPosition += (int) bytes;
                skipped += bytes;
                break;
            } else {
                skipped += remaining;
                bytes -= remaining;
                if (readIndex < writeIndex) {
                    buffers[readIndex++] = null;
                    readPosition = 0;
                } else {
                    assert readIndex == writeIndex;
                    ((Buffer) buffers[readIndex]).clear();
                    readPosition = 0;
                }
            }
        }
        return skipped;
    }

    public void peek(ByteBuffer dst) {
        int readIndex = this.readIndex;
        int readPosition = this.readPosition;
        while (dst.hasRemaining()) {
            if (readIndex >= writeIndex && buffers[readIndex].position() == readPosition) {
                break;
            }
            int p = buffers[readIndex].position();
            int l = buffers[readIndex].limit();
            try {
                ((Buffer) buffers[readIndex]).position(readPosition);
                ((Buffer) buffers[readIndex]).limit(p);
                if (buffers[readIndex].remaining() > dst.remaining()) {
                    ((Buffer) buffers[readIndex]).limit(dst.remaining());
                    dst.put(buffers[readIndex]);
                    break;
                } else {
                    dst.put(buffers[readIndex]);
                }
            } finally {
                ((Buffer) buffers[readIndex]).limit(l);
                ((Buffer) buffers[readIndex]).position(p);
            }
            readIndex++;
            readPosition = 0;
        }
    }

    /**
     * This method transfers bytes from the head of this buffer queue into the given destination buffer.
     * The number of bytes transferred will be the smaller of the number of bytes available and the remaining capacity
     * of the destination buffer.
     *
     * @param dst the destination buffer into which bytes are to be written.
     */
    public void get(ByteBuffer dst) {
        while (dst.hasRemaining()) {
            if (readIndex >= writeIndex && buffers[readIndex].position() == readPosition) {
                if (writeIndex > 0) {
                    // this is a cheap compact
                    buffers[0] = buffers[writeIndex];
                    buffers[writeIndex] = null;
                    readIndex = writeIndex = 0;
                }
                break;
            }
            ((Buffer) buffers[readIndex]).flip();
            if (buffers[readIndex].remaining() - readPosition > dst.remaining()) {
                int limit = buffers[readIndex].limit();
                ((Buffer) buffers[readIndex]).limit(readPosition + dst.remaining());
                ((Buffer) buffers[readIndex]).position(readPosition);
                dst.put(buffers[readIndex]);
                readPosition = buffers[readIndex].position();
                ((Buffer) buffers[readIndex]).limit(buffers[readIndex].capacity());
                ((Buffer) buffers[readIndex]).position(limit);
                break;
            } else {
                ((Buffer) buffers[readIndex]).position(readPosition);
                dst.put(buffers[readIndex]);
                readPosition = 0;
                if (readIndex < writeIndex) {
                    buffers[readIndex++] = null;
                } else {
                    assert readIndex == writeIndex;
                    ((Buffer) buffers[readIndex]).clear();
                }
            }
        }
    }

    /**
     * This method transfers bytes from the head of this buffer queue into the given destination {@link byte[]}.
     * The number of bytes transferred will be the smaller of the number of requested bytes and the remaining capacity
     * of the destination buffer.
     * @param dst the destination byte array into which the bytes are to be written.
     * @param offset the offset in the byte array at which to write the bytes.
     * @param len the number of bytes to transfer.
     * @return the actual number of bytes transferred.
     */
    public int get(byte[] dst, int offset, int len) {
        int read = 0;
        while (len > 0) {
            if (readIndex >= writeIndex && buffers[readIndex].position() == readPosition) {
                if (writeIndex > 0) {
                    // this is a cheap compact
                    buffers[0] = buffers[writeIndex];
                    buffers[writeIndex] = null;
                    readIndex = writeIndex = 0;
                }
                break;
            }
            ((Buffer) buffers[readIndex]).flip();
            ((Buffer) buffers[readIndex]).position(readPosition);
            int count = buffers[readIndex].remaining();
            if (count > len) {
                int limit = buffers[readIndex].limit();
                buffers[readIndex].get(dst, offset, len);
                read += len;
                readPosition = buffers[readIndex].position();
                ((Buffer) buffers[readIndex]).limit(buffers[readIndex].capacity());
                ((Buffer) buffers[readIndex]).position(limit);
                return read;
            } else {
                buffers[readIndex].get(dst, offset, count);
                offset += count;
                read += count;
                len -= count;
                readPosition = 0;
                if (readIndex < writeIndex) {
                    buffers[readIndex++] = null;
                } else {
                    assert readIndex == writeIndex;
                    ((Buffer) buffers[readIndex]).clear();
                }
            }
        }
        return read;
    }

    /**
     * Reads the next byte from this queue.
     *
     * @return The byte at the read index.
     * @throws BufferUnderflowException If there are no remaining bytes to be read.
     */
    public byte get() {
        int readLimit = buffers[readIndex].position();
        while (readIndex < writeIndex && readLimit == readPosition) {
            buffers[readIndex] = null;
            readIndex++;
            readPosition = 0;
            readLimit = buffers[readIndex].position();
        }
        if (readPosition < readLimit) {
            return buffers[readIndex].get(readPosition++);
        }
        throw new BufferUnderflowException();
    }

    /**
     * This method transfers the bytes remaining in the given source buffer inserted at the head of this buffer queue.
     *
     * @param src The source buffer from which bytes are to be read.
     */
    public void unget(ByteBuffer src) {
        if (!src.hasRemaining()) {
            return;
        }
        if (readPosition >= src.remaining()) {
            // very fast unget
            int l = buffers[readIndex].limit();
            int p = buffers[readIndex].position();
            ((Buffer) buffers[readIndex]).limit(readPosition);
            readPosition -= src.remaining();
            ((Buffer) buffers[readIndex]).position(readPosition);
            buffers[readIndex].put(src);
            ((Buffer) buffers[readIndex]).limit(l);
            ((Buffer) buffers[readIndex]).position(p);
            return;
        } else if (readPosition > 0) {
            // need to compact early
            ((Buffer) buffers[readIndex]).flip();
            ((Buffer) buffers[readIndex]).position(readPosition);
            buffers[readIndex].compact();
            readPosition = 0;
        }
        ByteBuffer[] inject = new ByteBuffer[src.remaining() / bufferSize + 1];
        int injectIndex = 0;
        while (src.hasRemaining()) {
            if (src.remaining() > bufferSize) {
                int limit = src.limit();
                ((Buffer) src).limit(src.position() + bufferSize);
                inject[injectIndex++] = newByteBuffer().put(src);
                ((Buffer) src).limit(limit);
            } else {
                inject[injectIndex++] = newByteBuffer().put(src);
            }
        }
        // we could try and cram the data into the current readIndex buffer, instead we will just put the data
        // into a new buffer that we insert before readIndex
        if (readIndex < injectIndex) {
            int injectCount = injectIndex - readIndex;
            while (writeIndex + injectCount >= buffers.length) {
                buffers = Arrays.copyOf(buffers, buffers.length * 2);
                shrinkCount = 0;
            }
            System.arraycopy(buffers, readIndex, buffers, injectIndex, writeIndex - readIndex + 1);
            writeIndex += injectCount;
            readIndex = 0;
            System.arraycopy(inject, 0, buffers, readIndex, injectIndex);
        } else {
            readIndex -= injectIndex;
            System.arraycopy(inject, 0, buffers, readIndex, injectIndex);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return getClass().getName() + "[hasRemaining="
                + hasRemaining()
                + ",readIndex="
                + readIndex
                + ",writeIndex="
                + writeIndex
                + ",capacity="
                + buffers.length
                + ",bufSize="
                + bufferSize
                + ']';
    }

    /**
     * Transfers all the bytes in the {@link ByteBufferQueue} from the read position to the write position into a new
     * {@code byte[]}.
     *
     * @return the {@code byte[]}
     */
    public byte[] toByteArray() {
        if (readIndex == writeIndex) {
            if (buffers[readIndex].position() == readPosition) {
                return EMPTY_BYTE_ARRAY;
            }
            ((Buffer) buffers[readIndex]).flip();
            ((Buffer) buffers[readIndex]).position(readPosition);
            byte[] result = new byte[buffers[readIndex].remaining()];
            buffers[readIndex].get(result);
            ((Buffer) buffers[readIndex]).clear();
            readPosition = 0;
            readIndex = writeIndex = 0;
            return result;
        }
        int size = 0;
        for (int index = readIndex; index <= writeIndex; index++) {
            size += buffers[index].position();
        }
        byte[] result = new byte[size];
        int pos = 0;
        for (int index = readIndex; index <= writeIndex; index++) {
            ((Buffer) buffers[index]).flip();
            ((Buffer) buffers[index]).position(readPosition);
            int count = buffers[index].remaining();
            buffers[index].get(result, pos, count);
            ((Buffer) buffers[index]).clear();
            readPosition = 0;
            pos += count;
        }
        readIndex = writeIndex = 0;
        return result;
    }
}
