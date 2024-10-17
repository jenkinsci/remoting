/*
 * The MIT License
 *
 * Copyright 2024 CloudBees, Inc.
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

import java.net.Inet6Address;
import org.junit.Test;

public final class JnlpAgentEndpointResolverTest {

    /** @see Inet6Address */
    @Test
    public void getResolvedHttpProxyAddressIPv6() throws Exception {
        JnlpAgentEndpointResolver.getResolvedHttpProxyAddress("localhost", 12345);
        JnlpAgentEndpointResolver.getResolvedHttpProxyAddress("127.0.0.1", 12345);
        // Ignore return value, just assert that it does not throw an exception:
        JnlpAgentEndpointResolver.getResolvedHttpProxyAddress("0:0:0:0:0:0:0:1%lo", 12345);
    }

}
