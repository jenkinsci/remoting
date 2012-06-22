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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;


/**
 * An input stream that captures a snippet of what was read from it.
 * 
 * @author Dean Yu
 */
public class CapturingInputStream extends InputStream {
    private RingBuffer<byte[]> capture;
    private InputStream underlyingStream;

    public CapturingInputStream(InputStream is) {
        this(is, 1024);
    }

    public CapturingInputStream(InputStream is, int captureLast) {
        this(is, 0, captureLast);
    }

    public CapturingInputStream(InputStream is, int captureFirst, int captureLast) {
        this.capture = new RingBuffer<byte[]>(byte[].class, captureFirst, captureLast);
        this.underlyingStream = is;
    }

    @Override
    public int available() throws IOException {
        return underlyingStream.available();
    }

    @Override
    public void close() throws IOException {
        underlyingStream.close();
    }

    @Override
    public void mark(int readLimit) {
        underlyingStream.mark(readLimit);
    }

    @Override
    public boolean markSupported() {
        return underlyingStream.markSupported();
    }

    @Override
    public int read(byte[] b) throws IOException {
        capture(0, b.length);
        return underlyingStream.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        capture(off, len);
        return underlyingStream.read(b, off, len);
    }

    @Override
    public int read() throws IOException {
        capture(0, -1);
        return underlyingStream.read();
    }

    public void dump() {
        this.capture.dump();
    }
    
    private void capture(int off, int len) throws IOException {
        if (underlyingStream.markSupported()) {
            byte[] b;

            if (len == -1) {
                len = underlyingStream.available();
            }
            b = new byte[off+len];
            
            try {
                underlyingStream.mark(len+1);
                underlyingStream.read(b, off, len);
                capture.add(off==0? b : Arrays.copyOfRange(b, off, len));
            } finally {
                underlyingStream.reset();
            }
        }
    }
}
