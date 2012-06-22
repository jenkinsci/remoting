/*
 * The MIT License
 *
 * Copyright 2012 Yahoo!, Inc..
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

import java.io.PrintStream;
import java.lang.reflect.Array;


/**
 * A ring buffer implementation for debugging and diagnostics.
 *
 * This implementation supports remembering the first 'n' and last 'm' items
 * added to the buffer.
 *
 * @author Dean Yu
 */
public class RingBuffer<T> {
    private T[] buffer;
    private int headSize;
    private int tailSize;
    private int numAdds;

    
    /**
     * Creates a new ring buffer.
     *
     * The ring buffer will have space to store the first {@code head} items added to
     * the buffer, and the last {@code tail} items added to the buffer.
     *
     * To capture just the first {@code head} items added to the buffer,
     * pass 0 for {@code tail}.
     *
     * To capture just the last {@code tail} items added to the buffer,
     * pass 0 for {@code head}.
     *
     * {@code head} and {@code tail} must not both be 0.
     * 
     * @param head Keep first {@code head} items added to the buffer
     * @param tail Keep the last {@code tail} items added to the buffer
     * @throws IllegalArgumentException if both head and tail are 0
     */
    public RingBuffer(Class<T> clazz, int head, int tail) {
        if ((head == 0) && (tail == 0)) {
            throw new IllegalArgumentException("head and tail cannot both be 0");
        }
        
        this.buffer = (T[]) Array.newInstance(clazz, head + tail);
        this.headSize = head;
        this.tailSize = tail;
        this.numAdds = 0;
    }

    /**
     * Adds an item to the ring buffer.
     *
     * Returns the buffer to simplify composition.
     * 
     * @param item
     */
    public RingBuffer<T> add(T item) {
        int index = calcAddIndex();
        if (index != -1) {
            this.buffer[index] = item;
        }
        this.numAdds++;

        return this;
    }

    /**
     * Returns the total number of times {@link #add(java.lang.Object)} was called.
     * 
     * @return 
     */
    public int getNumAdds() {
        return this.numAdds;
    }


    /**
     * Dumps the contents of the ring buffer to stderr.
     *
     * This is the same as calling
     * <blockquote>
     * <code>
         dump(System.err);
       </code>
     * </blockquote>
     */
    public void dump() {
        dump(System.err);
    }

    /**
     * Dumps the contents of the ring buffer to the specified print stream.
     * 
     * @param stream
     */
    public void dump(PrintStream stream) {
        stream.printf("Buffer stats: %d head items, %d tail items, %d total adds\n",
                this.headSize, this.tailSize, this.numAdds);

        if (this.headSize > 0) {
            stream.println("Head items");
            for (int i = 0; i < this.headSize; i++) {
                stream.println(this.buffer[i].toString());
            }
        }

        if (this.tailSize > 0 && this.numAdds > this.headSize) {
            stream.println("Tail items");
            for (int i = 0; i < this.tailSize; i++) {
                stream.println(this.buffer[calcTailLoopIndex(i)].toString());
            }
        }
    }

    /**
     * Returns the index into the buffer that the next call to {@link #add(java.lang.Object)}
     * inserts at.
     * 
     * @return 0 based index, or -1 if no insertion would happen
     */
    protected int calcAddIndex() {
        if (this.numAdds < this.headSize) {
            return this.numAdds;
        } else {
            return calcTailIndex();
        }
    }

    /**
     * Returns the index into the buffer that the next tail element will be
     * stored at.
     *
     * @return 0 based index, or -1 if next add would not store an element
     */
    protected int calcTailIndex() {
        if (this.tailSize > 0) {
            return (this.numAdds % this.tailSize) + this.headSize;
        } else {
            return -1;
        }
    }

    /**
     * Converts a 0 based index into an index into the buffer storage.
     *
     * This is used to return a stored tail item.
     * 
     * @param index
     * @return
     */
    protected int calcTailLoopIndex(int index) {
        if (index >= this.tailSize) {
            throw new IndexOutOfBoundsException("index must be less than tail size");
        }
        int base = calcTailIndex();
        return ((base + index) % this.tailSize) + this.headSize;
    }

    // Exposed for testing
    T[] getBuffer() {
        return (T[])this.buffer;
    }
}
