/*
 * The MIT License
 *
 * Copyright 2012 Yahoo!, Inc.
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

/**
 * Utility class to dump the contents of a {@link RingBuffer} in order.
 *
 * The default implementation simply calls toString on each item in the
 * ring buffer. Custom formats for specific data types can be implemented by
 * creating a subclass and overriding {@link #dumpOne }.
 *
 * @see ByteArrayRingBufferDumper
 * @author Dean Yu
 */
public class RingBufferDumper {
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
    public void dump(RingBuffer ring) {
        dump(ring, System.err);
    }

    /**
     * Dumps the contents of the ring buffer to the specified print stream.
     *
     * @param stream
     */
    public void dump(RingBuffer ring, PrintStream stream) {
        stream.printf("Buffer stats: %d head items, %d tail items, %d total adds\n",
                ring.getHeadSize(), ring.getTailSize(), ring.getNumAdds());

        if (ring.getHeadSize() > 0) {
            stream.println("Head items");
            for (int i = 0; i < ring.getHeadSize(); i++) {
                dumpOne(ring.getBuffer()[i], stream);
           }
        }

        if (ring.getTailSize() > 0 && ring.getNumAdds() > ring.getHeadSize()) {
            stream.println("Tail items");
            for (int i = 0; i < ring.getTailSize(); i++) {
                dumpOne(ring.getBuffer()[i], stream);
            }
        }
    }

    protected void dumpOne(Object item, PrintStream stream) {
        stream.println(item.toString());
    }
}
