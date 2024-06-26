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
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

/**
 * {@link InputStream} connected to socket.
 *
 * <p>
 * Unlike plain {@link Socket#getOutputStream()}, closing the stream
 * does not close the entire socket, and instead it merely partial-close
 * a socket in the direction.
 *
 * @author Kohsuke Kawaguchi
 */
public class SocketOutputStream extends FilterOutputStream {
    private final Socket socket;

    /**
     * @deprecated
     *      Use {@link SocketChannelStream#out(Socket)}
     */
    @Deprecated
    public SocketOutputStream(Socket socket) throws IOException {
        super(socket.getOutputStream());
        this.socket = socket;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
    }

    @Override
    public void write(@NonNull byte[] b) throws IOException {
        out.write(b);
    }

    @Override
    public void write(@NonNull byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
        if (socket.isClosed()) {
            return;
        }
        if (!socket.isOutputShutdown()) {
            socket.shutdownOutput();
        }
        if (socket.isInputShutdown()) {
            socket.close();
        }
    }
}
