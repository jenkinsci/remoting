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
package org.jenkinsci.remoting.engine;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.when;

/**
 * Tests for {@link JnlpProtocol2}.
 *
 * @author Akshay Dayal
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({EngineUtil.class, JnlpProtocol2.class})
public class JnlpProtocol2Test {

    private static final String SECRET = "secret";
    private static final String SLAVE_NAME = "slave-name";
    private static final String COOKIE = "some-cookie";
    private static final String COOKIE2 = "some-other-cookie";

    @Mock private DataOutputStream mockDataOutputStream;
    @Mock private BufferedInputStream mockBufferedInputStream;
    private JnlpProtocol2 protocol;
    private InOrder inOrder;

    @Before
    public void setUp() throws Exception {
        protocol = new JnlpProtocol2(SECRET, SLAVE_NAME);
        inOrder = inOrder(mockDataOutputStream);
        mockStatic(EngineUtil.class);
    }

    @Test
    public void testGetName() {
        assertEquals("JNLP2-connect", protocol.getName());
    }

    @Test
    public void testHandshakeError() throws Exception {
        Properties expectedProperties = new Properties();
        expectedProperties.put(JnlpProtocol2.SECRET_KEY, SECRET);
        expectedProperties.put(JnlpProtocol2.SLAVE_NAME_KEY, SLAVE_NAME);
        ByteArrayOutputStream expectedPropertiesStream = new ByteArrayOutputStream();
        expectedProperties.store(expectedPropertiesStream, null);

        when(EngineUtil.readLine(mockBufferedInputStream)).thenReturn("error");

        assertEquals("error",
                protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP2-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(argThat(
                new PropertiesStringMatcher(expectedPropertiesStream.toString("UTF-8"))));
    }

    @Test
    public void testHandshakeSuccess() throws Exception {
        // Properties slave sends to master.
        Properties expectedProperties = new Properties();
        expectedProperties.put(JnlpProtocol2.SECRET_KEY, SECRET);
        expectedProperties.put(JnlpProtocol2.SLAVE_NAME_KEY, SLAVE_NAME);
        ByteArrayOutputStream expectedPropertiesStream = new ByteArrayOutputStream();
        expectedProperties.store(expectedPropertiesStream, null);
        // Properties master sends back.
        Properties responseProperties = new Properties();
        responseProperties.put(JnlpProtocol2.COOKIE_KEY, COOKIE);

        when(EngineUtil.readLine(mockBufferedInputStream))
                .thenReturn(JnlpProtocol.GREETING_SUCCESS);
        when(EngineUtil.readResponseHeaders(mockBufferedInputStream))
                .thenReturn(responseProperties);

        assertEquals(JnlpProtocol.GREETING_SUCCESS,
                protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));
        assertEquals(COOKIE, protocol.getCookie());

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP2-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(argThat(
                new PropertiesStringMatcher(expectedPropertiesStream.toString("UTF-8"))));
    }

    @Test
    public void testRepeatedHandshakeSendsCookie() throws Exception {
        // Properties slave sends to master the first time.
        Properties expectedProperties = new Properties();
        expectedProperties.put(JnlpProtocol2.SECRET_KEY, SECRET);
        expectedProperties.put(JnlpProtocol2.SLAVE_NAME_KEY, SLAVE_NAME);
        ByteArrayOutputStream expectedPropertiesStream = new ByteArrayOutputStream();
        expectedProperties.store(expectedPropertiesStream, null);

        // Properties master sends back first time.
        Properties responseProperties = new Properties();
        responseProperties.put(JnlpProtocol2.COOKIE_KEY, COOKIE);

        // Properties slave sends to master the second time.
        Properties expectedProperties2 = new Properties();
        expectedProperties2.put(JnlpProtocol2.SECRET_KEY, SECRET);
        expectedProperties2.put(JnlpProtocol2.SLAVE_NAME_KEY, SLAVE_NAME);
        expectedProperties2.put(JnlpProtocol2.COOKIE_KEY, COOKIE);
        ByteArrayOutputStream expectedPropertiesStream2 = new ByteArrayOutputStream();
        expectedProperties2.store(expectedPropertiesStream2, null);

        // Properties master sends back second time.
        Properties responseProperties2 = new Properties();
        responseProperties2.put(JnlpProtocol2.COOKIE_KEY, COOKIE2);

        when(EngineUtil.readLine(mockBufferedInputStream))
                .thenReturn(JnlpProtocol.GREETING_SUCCESS);
        when(EngineUtil.readResponseHeaders(mockBufferedInputStream)).thenReturn(responseProperties)
                .thenReturn(responseProperties2);

        assertEquals(JnlpProtocol.GREETING_SUCCESS,
                protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));
        assertEquals(COOKIE, protocol.getCookie());
        assertEquals(JnlpProtocol.GREETING_SUCCESS,
                protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));
        assertEquals(COOKIE2, protocol.getCookie());

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP2-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(argThat(
                new PropertiesStringMatcher(expectedPropertiesStream.toString("UTF-8"))));
        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP2-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(argThat(
                new PropertiesStringMatcher(expectedPropertiesStream2.toString("UTF-8"))));
    }
}
