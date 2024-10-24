/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Classic splitter of {@link OutputStream}. Named after the Unix 'tee' command. It allows a stream
 * to be branched off so there are now two streams.
 */
@Restricted(NoExternalUse.class)
public class TeeOutputStream extends FilterOutputStream {

    /**
     * The second OutputStream to write to.
     *
     * <p>TODO Make private and final in 3.0.
     */
    protected OutputStream branch;

    /**
     * Constructs a TeeOutputStream.
     *
     * @param out the main OutputStream
     * @param branch the second OutputStream
     */
    public TeeOutputStream(final OutputStream out, final OutputStream branch) {
        super(out);
        this.branch = branch;
    }

    /**
     * Writes the bytes to both streams.
     *
     * @param b the bytes to write
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public synchronized void write(@NonNull final byte[] b) throws IOException {
        out.write(b);
        this.branch.write(b);
    }

    /**
     * Writes the specified bytes to both streams.
     *
     * @param b the bytes to write
     * @param off The start offset
     * @param len The number of bytes to write
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public synchronized void write(@NonNull final byte[] b, final int off, final int len) throws IOException {
        out.write(b, off, len);
        this.branch.write(b, off, len);
    }

    /**
     * Writes a byte to both streams.
     *
     * @param b the byte to write
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public synchronized void write(final int b) throws IOException {
        out.write(b);
        this.branch.write(b);
    }

    /**
     * Flushes both streams.
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void flush() throws IOException {
        out.flush();
        this.branch.flush();
    }

    /**
     * Closes both output streams.
     *
     * <p>If closing the main output stream throws an exception, attempt to close the branch output
     * stream.
     *
     * <p>If closing the main and branch output streams both throw exceptions, which exceptions is
     * thrown by this method is currently unspecified and subject to change.
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void close() throws IOException {
        try {
            if (out != null) {
                out.close();
            }
        } finally {
            this.branch.close();
        }
    }
}
