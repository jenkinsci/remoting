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

import java.util.ArrayList;
import java.util.List;

/**
 * Creates protocols to be used to initiate connection with master.
 *
 * @author Akshay Dayal
 */
public class JnlpProtocolFactory {

    /**
     * Create the list of protocols the JNLP slave should attempt when
     * connecting to the master.
     *
     * The protocols should be tried in the order they are given.
     */
    public static List<JnlpProtocol> createProtocols(String secretKey, String slaveName) {
        List<JnlpProtocol> protocols = new ArrayList<JnlpProtocol>();
        // New protocols should be tried first.
        protocols.add(new JnlpProtocol2(secretKey, slaveName));
        protocols.add(new JnlpProtocol1(secretKey, slaveName));

        return protocols;
    }
}
