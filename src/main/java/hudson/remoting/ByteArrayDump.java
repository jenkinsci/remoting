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
import java.util.Formatter;

/**
 * Dumps a byte array into hex/ascii format.
 *
 * @author Dean Yu
 */
public class ByteArrayDump {
    public static String dump(byte[] buf) {
        int len = buf.length;
        int rows = len / 16;
        int mod = len % 16;

        StringBuilder builder = new StringBuilder(buf.length);
        Formatter f = new Formatter(builder);

        for (int row = 0; row < rows; row++) {
            f.format("%04x: ", row * 16);
            dumpRow(f, buf, row * 16, 16);
        }

        if (mod > 0) {
            f.format("%04x: ", rows * 16);
            for (int index = 0; index < mod; index++) {
                dumpRow(f, buf, rows * 16, mod);
            }
        }

        f.flush();
        return builder.toString();
    }

    private static void dumpRow(Formatter f, byte[] buf, int off, int len) {
        for (int i = 0; i < len; i++) {
            f.format("%02x ", buf[off + i]);
        }
        for (int i = len; i < 16; i++) {
            f.format("   ");
        }
        
        f.format("    ");
        for (int i = 0; i < len; i++) {
            if (buf[off + i] < 32) {
                f.format("%c", '.');
            } else {
                f.format("%c", (char)(buf[off+i] & 0xff));
            }
        }
        f.format("\n");
        f.flush();
    }
}
