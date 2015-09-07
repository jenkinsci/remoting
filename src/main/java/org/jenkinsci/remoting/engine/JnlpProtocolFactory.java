/*
 * The MIT License
 * 
 * Copyright (c) 2004-2015, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
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
package org.jenkinsci.remoting.engine;

import hudson.remoting.EngineListener;
import hudson.remoting.EngineListenerSplitter;

import java.util.Arrays;
import java.util.List;

/**
 * Creates protocols to be used to initiate connection with master.
 *
 * The slave engine will call this factory once when it starts and try the
 * protocols in the order they are returned.
 *
 * @author Akshay Dayal
 */
public class JnlpProtocolFactory {

    /**
     * Create the list of protocols the JNLP slave should attempt when
     * connecting to the master.
     *
     * The protocols should be tried in the order they are given.
     *
     * @param slaveName The name of the registered slave.
     * @param slaveSecret The secret associated with the slave.
     * @param events The {@link EngineListener} that the protocol shall send events to.
     */
    public static List<JnlpProtocol> createProtocols(String slaveName, String slaveSecret,
            EngineListener events) {
        return Arrays.asList(
            new JnlpProtocol2(slaveName, slaveSecret, events),
            new JnlpProtocol1(slaveName, slaveSecret, events)
        );
    }

    /**
     * @deprecated as of 2.51. Use {@link #createProtocols(String, String, EngineListener)}.
     */
    public static List<JnlpProtocol> createProtocols(String slaveSecret, String slaveName) {
        return createProtocols(slaveName,slaveSecret,new EngineListenerSplitter());
    }

}
