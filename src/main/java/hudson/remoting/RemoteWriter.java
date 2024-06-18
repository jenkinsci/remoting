/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Writer;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

/**
 * {@link Writer} that can be sent over to the remote {@link Channel},
 * so that the remote {@link Callable} can write to a local {@link Writer}.
 *
 * <h2>Usage</h2>
 * <pre>
 * final Writer out = new RemoteWriter(w);
 *
 * channel.call(new Callable() {
 *   public Object call() {
 *     // this will write to 'w'.
 *     out.write(...);
 *   }
 * });
 * </pre>
 *
 * @see RemoteInputStream
 * @author Kohsuke Kawaguchi
 */
public final class RemoteWriter extends Writer implements SerializableOnlyOverRemoting {
    /**
     * On local machine, this points to the {@link Writer} where
     * the data will be sent ultimately.
     *
     * On remote machine, this points to {@link ProxyOutputStream} that
     * does the network proxy.
     */
    private transient Writer core;

    public RemoteWriter(Writer core) {
        this.core = core;
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        int id = getChannelForSerialization()
                .internalExport(Writer.class, core, false); // this export is unexported in ProxyWriter.finalize()
        oos.writeInt(id);
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        final Channel channel = getChannelForSerialization();
        this.core = new ProxyWriter(channel, ois.readInt());
    }

    private static final long serialVersionUID = 1L;

    //
    //
    // delegation to core
    //
    //
    @Override
    public void write(int c) throws IOException {
        core.write(c);
    }

    @Override
    public void write(@NonNull char[] cbuf) throws IOException {
        core.write(cbuf);
    }

    @Override
    public void write(@NonNull char[] cbuf, int off, int len) throws IOException {
        core.write(cbuf, off, len);
    }

    @Override
    public void write(@NonNull String str) throws IOException {
        core.write(str);
    }

    @Override
    public void write(@NonNull String str, int off, int len) throws IOException {
        core.write(str, off, len);
    }

    @Override
    public Writer append(CharSequence csq) throws IOException {
        return core.append(csq);
    }

    @Override
    public Writer append(CharSequence csq, int start, int end) throws IOException {
        return core.append(csq, start, end);
    }

    @Override
    public Writer append(char c) throws IOException {
        return core.append(c);
    }

    @Override
    public void flush() throws IOException {
        core.flush();
    }

    @Override
    public void close() throws IOException {
        core.close();
    }
}
