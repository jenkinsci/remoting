/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
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
package hudson.remoting.engine;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Handshake protocol used by JNLP slave when initiating a connection to
 * master.
 *
 * @author Akshay Dayal
 */
public abstract class JnlpProtocol {

    final String secretKey;
    final String slaveName;

    JnlpProtocol(String secretKey, String slaveName) {
        this.secretKey = secretKey;
        this.slaveName = slaveName;
    }

    /**
     * Get the name of the protocol.
     */
    public abstract String getName();

    /**
     * Performs a handshake with the master.
     *
     * @param outputStream The stream to write into to initiate the handshake.
     * @param inputStream The stream to read responses from the master.
     * @return The greeting response from the master.
     * @throws IOException
     */
    public abstract String performHandshake(DataOutputStream outputStream,
            BufferedInputStream inputStream) throws IOException;

    // The expected response from the master on successful completion of the
    // handshake.
    public static final String GREETING_SUCCESS = "Welcome";

    // Prefix when sending protocol name.
    static final String PROTOCOL_PREFIX = "Protocol:";
}
