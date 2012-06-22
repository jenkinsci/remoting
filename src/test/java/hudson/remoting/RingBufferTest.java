/*
 * The MIT License
 *
 * Copyright 2012 dty.
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

import junit.framework.TestCase;

/**
 *
 * @author dty
 */
public class RingBufferTest extends TestCase {
    public void testHead() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 3, 0);

        r.add(0).add(1).add(2);
        assertEquals(3, r.getNumAdds());

        Integer[] buffer = r.getBuffer();
        assertEquals(3, buffer.length);
        assertEquals(0, buffer[0].intValue());
        assertEquals(1, buffer[1].intValue());
        assertEquals(2, buffer[2].intValue());
    }

    public void testHeadOverflow() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 3, 0);

        r.add(0).add(1).add(2).add(3).add(4);
        assertEquals(5, r.getNumAdds());

        Integer[] buffer = r.getBuffer();
        assertEquals(3, buffer.length);
        assertEquals(0, buffer[0].intValue());
        assertEquals(1, buffer[1].intValue());
        assertEquals(2, buffer[2].intValue());
    }

    public void testHeadUnfilled() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 3, 0);

        r.add(0).add(1);
        assertEquals(2, r.getNumAdds());

        Integer[] buffer = r.getBuffer();
        assertEquals(3, buffer.length);
        assertEquals(0, buffer[0].intValue());
        assertEquals(1, buffer[1].intValue());
        assertNull(buffer[2]);
    }
        
    public void testTail() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 0, 3);

        r.add(0).add(1).add(2);
        assertEquals(3, r.getNumAdds());

        Integer[] buffer = r.getBuffer();
        assertEquals(3, buffer.length);
        assertEquals(0, buffer[0].intValue());
        assertEquals(1, buffer[1].intValue());
        assertEquals(2, buffer[2].intValue());
    }

    public void testRing() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 0, 3);

        r.add(0).add(1).add(2).add(3).add(4);
        assertEquals(5, r.getNumAdds());

        Integer[] buffer = r.getBuffer();
        assertEquals(3, buffer.length);
        assertEquals(3, buffer[0].intValue());
        assertEquals(4, buffer[1].intValue());
        assertEquals(2, buffer[2].intValue());
    }

    public void testHeadTail() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 3, 3);

        r.add(0).add(1).add(2).add(3).add(4).add(5);
        assertEquals(6, r.getNumAdds());

        Integer[] buffer = r.getBuffer();
        assertEquals(6, buffer.length);
        assertEquals(0, buffer[0].intValue());
        assertEquals(1, buffer[1].intValue());
        assertEquals(2, buffer[2].intValue());
        assertEquals(3, buffer[3].intValue());
        assertEquals(4, buffer[4].intValue());
        assertEquals(5, buffer[5].intValue());
    }

    public void testHeadTailUnfilled() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 3, 3);

        r.add(0).add(1).add(2);
        assertEquals(3, r.getNumAdds());

        Integer[] buffer = r.getBuffer();
        assertEquals(6, buffer.length);
        assertEquals(0, buffer[0].intValue());
        assertEquals(1, buffer[1].intValue());
        assertEquals(2, buffer[2].intValue());
        assertNull(buffer[3]);
        assertNull(buffer[4]);
        assertNull(buffer[5]);
    }

    public void testHeadTailRing() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 3, 3);

        r.add(0).add(1).add(2).add(3).add(4).add(5).add(6).add(7).add(8).add(9);
        assertEquals(10, r.getNumAdds());

        Integer[] buffer = r.getBuffer();
        assertEquals(6, buffer.length);
        assertEquals(0, buffer[0].intValue());
        assertEquals(1, buffer[1].intValue());
        assertEquals(2, buffer[2].intValue());
        assertEquals(9, buffer[3].intValue());
        assertEquals(7, buffer[4].intValue());
        assertEquals(8, buffer[5].intValue());
    }

    public void testTailLoopIndex() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 3, 3);

        r.add(0).add(1).add(2).add(3).add(4).add(5).add(6).add(7).add(8);
        assertEquals(3, r.calcTailLoopIndex(0));
        assertEquals(4, r.calcTailLoopIndex(1));
        assertEquals(5, r.calcTailLoopIndex(2));

        try {
            r.calcTailLoopIndex(3);
            fail("calcTailLoopIndex should have thrown IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException e) {
            // success
        }
    }

    public void testTailLoopIndex2() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 3, 3);

        r.add(0).add(1).add(2).add(3).add(4).add(5).add(6).add(7).add(8).add(9);
        assertEquals(4, r.calcTailLoopIndex(0));
        assertEquals(5, r.calcTailLoopIndex(1));
        assertEquals(3, r.calcTailLoopIndex(2));
    }

    public void testTailLoopIndex3() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 3, 3);

        r.add(0).add(1).add(2).add(3).add(4).add(5).add(6).add(7).add(8).add(9).add(10);
        assertEquals(5, r.calcTailLoopIndex(0));
        assertEquals(3, r.calcTailLoopIndex(1));
        assertEquals(4, r.calcTailLoopIndex(2));
    }

    public void testInvalidTailLoopIndex() {
        RingBuffer<Integer> r = new RingBuffer<Integer>(Integer.class, 3, 3);

        r.add(0).add(1).add(2).add(3).add(4).add(5).add(6).add(7).add(8);

        try {
            r.calcTailLoopIndex(3);
            fail("calcTailLoopIndex should have thrown IndexOutOfBoundsException");
        } catch (IndexOutOfBoundsException e) {
            // success
        }
    }
}
