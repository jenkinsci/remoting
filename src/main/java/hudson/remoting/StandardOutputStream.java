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

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Hint that indicates that we are using {@code stdout} with fd=1 as the stream to write to for the channel.
 *
 * <p>
 * Using stdin/stdout pair for the communication is very convenient for setting up a remote channel,
 * so Jenkins tends to do that, but even with our using {@link System#setOut(PrintStream)} to avoid
 * other parts of the Java code to accidentally corrupt the stream, file descriptor 1 continues to
 * point to the stream we use, so native code in JVM can still accidentally pollute the stream.
 *
 * <p>
 * Fixing that requires the use of dup and close POSIX API calls, which we can only do after the communication
 * gets established and JNA is brought in via remoting. Having {@link Launcher} uses this wrapper class allows
 * us to do that without recreating the stream.
 *
 * <p>
 * When a channel uses {@link StandardOutputStream} to communicate, the convention is to make that object
 * available as a channel property.
 *
 * @author Kohsuke Kawaguchi
 */
public class StandardOutputStream extends OutputStream {
    private OutputStream out;
    private boolean swapped;

    public StandardOutputStream() {
        this.out = System.out;
    }

    /**
     * Atomically swaps the underlying stream to another stream and returns it.
     */
    public synchronized OutputStream swap(OutputStream another) {
        // if the stream is already swapped, the caller's implicit assumption that this represents stdout
        // is wrong, so we raise an error.
        if (swapped) {
            throw new IllegalStateException();
        }
        OutputStream old = out;
        out = another;
        swapped = true;
        return old;
    }

    public synchronized boolean isSwapped() {
        return swapped;
    }

    @Override
    public synchronized void write(int b) throws IOException {
        out.write(b);
    }

    @Override
    public synchronized void write(@NonNull byte[] b) throws IOException {
        out.write(b);
    }

    @Override
    public synchronized void write(@NonNull byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    @Override
    public synchronized void flush() throws IOException {
        out.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
