/*
 * The MIT License
 *
 * Copyright (c) 2011, CloudBees, Inc.
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
import java.io.OutputStream;
import java.util.zip.CRC32;

/**
 * InputSteram/OutputStream wrapper that adds CRC32-based framing, so that the corruption
 * of a stream can be detected quickly.
 *
 * @author Kohsuke Kawaguchi
 */
public class FramedStream {
    public static OutputStream wrap(OutputStream out, int frameSize) {
        return new FrameOutputStream(out,frameSize);
    }

    private static class FrameOutputStream extends OutputStream {
        private final OutputStream out;
        private final byte[] buf;
        /**
         * Size kept in the buffer.
         */
        private int len;

        private final CRC32 crc = new CRC32();

        /**
         * Frame header. 2 byte for length (up to 64K), 4 byte CRC-32.
         */
        private final byte[] header = new byte[6];

        public FrameOutputStream(OutputStream out, int frameSize) {
            this.out = out;
            if (frameSize>=65536)
                throw new IllegalArgumentException("Frame size is too big. Must be less than 64K");
            this.buf = new byte[frameSize];
        }

        @Override
        public synchronized void write(byte[] b, int off, int sz) throws IOException {
            while (sz>0) {
                int chunk = Math.min(capacity(),sz);
                System.arraycopy(b,off,buf,len,chunk);
                sz -= chunk;
                off += chunk;
                if (isBufferFull())
                    ship();
            }
        }

        @Override
        public synchronized void write(int b) throws IOException {
            buf[len++] = (byte)b;
            if (isBufferFull())
                ship();
        }

        @Override
        public synchronized void flush() throws IOException {
            if (len>0)
                ship();
            out.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            if (len>0)
                ship();
            out.close();
        }

        /**
         * Did our local buffer fill up?
         */
        private boolean isBufferFull() {
            return buf.length==len;
        }

        /**
         * How many bytes can we still hold in our buffer?
         */
        private int capacity() {
            return buf.length-len;
        }

        /**
         * Send out one frame.
         */
        private void ship() throws IOException {
            crc.reset();
            crc.update(buf,0,len);
            int v = (int)crc.getValue();

            header[0] = (byte)(len>>8);
            header[1] = (byte)(len&0xFF);
            header[2] = (byte)((v>>24)&0xFF);
            header[3] = (byte)((v>>16)&0xFF);
            header[4] = (byte)((v>> 8)&0xFF);
            header[5] = (byte)((v    )&0xFF);
            out.write(header);
            out.write(buf,0,len);
        }
    }
}
