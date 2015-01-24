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
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Implementation of the JNLP2-connect protocol.
 *
 * @author Akshay Dayal
 */
class JnlpProtocol2 extends JnlpProtocol {

    /**
     * This cookie identifies the current connection, allowing us to force the server to drop
     * the client if we initiate a reconnection from our end (even when the server still thinks
     * the connection is alive.)
     */
    private String cookie;

    JnlpProtocol2(String secretKey, String slaveName) {
        super(secretKey, slaveName);
    }

    @Override
    public String getName() {
        return NAME;
    }

    String getCookie() {
        return cookie;
    }

    @Override
    public String performHandshake(DataOutputStream outputStream,
            BufferedInputStream inputStream) throws IOException {
        initiateHandshake(outputStream);

        // Get the response from the master,
        String response = EngineUtil.readLine(inputStream);

        // If success, look for the cookie.
        if (response.equals(GREETING_SUCCESS)) {
            Properties responses = EngineUtil.readResponseHeaders(inputStream);
            cookie = responses.getProperty(COOKIE_KEY);
        }

        return response;
    }

    private void initiateHandshake(DataOutputStream outputStream) throws IOException {
        Properties props = new Properties();
        props.put(SECRET_KEY, secretKey);
        props.put(SLAVE_NAME_KEY, slaveName);

        // If there is a cookie send that as well.
        if (cookie != null)
            props.put(COOKIE_KEY, cookie);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        props.store(o, null);

        outputStream.writeUTF(PROTOCOL_PREFIX + NAME);
        outputStream.writeUTF(o.toString("UTF-8"));
    }

    static final String NAME = "JNLP2-connect";
    static final String SECRET_KEY = "Secret-Key";
    static final String SLAVE_NAME_KEY = "Node-Name";
    static final String COOKIE_KEY = "Cookie";
}
