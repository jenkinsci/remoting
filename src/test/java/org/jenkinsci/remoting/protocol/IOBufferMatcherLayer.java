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
package org.jenkinsci.remoting.protocol;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * An {@link ApplicationLayer} that produces a {@link IOBufferMatcher}
 *
 * @since 3.0
 */
public class IOBufferMatcherLayer extends ApplicationLayer<IOBufferMatcher> {

    private final IOBufferMatcher app;

    public IOBufferMatcherLayer() {
        this(null);
    }

    public IOBufferMatcherLayer(String name) {
        app = new IOBufferMatcher(name) {
            @Override
            public void send(ByteBuffer data) throws IOException {
                write(data);
            }

            @Override
            public void close() throws IOException {
                close(null);
            }

            @Override
            public void close(IOException cause) throws IOException {
                doCloseWrite();
                super.close(cause);
            }
        };
    }

    @Override
    public void start() {}

    @Override
    public IOBufferMatcher get() {
        return app;
    }

    @Override
    public boolean isReadOpen() {
        return app.isOpen();
    }

    @Override
    public void onRead(@NonNull ByteBuffer data) {
        app.receive(data);
    }

    @Override
    public void onReadClosed(IOException cause) throws IOException {
        app.close(cause);
    }
}
