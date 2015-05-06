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
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.StringBufferInputStream;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.when;
import static org.powermock.api.mockito.PowerMockito.whenNew;

/**
 * Tests for {@link JnlpProtocol2}.
 *
 * @author Akshay Dayal
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({JnlpProtocol2.class, EngineUtil.class})
public class JnlpProtocol2Test {

    private static final String SECRET = "secret";
    private static final String SLAVE_NAME = "slave-name";
    private static final String COOKIE = "some-cookie";
    private static final String COOKIE2 = "some-other-cookie";

    private JnlpProtocol2 protocol;
    @Mock private DataOutputStream outputStream;
    @Mock private BufferedInputStream inputStream;

    @Before
    public void setUp() throws Exception {
        protocol = new JnlpProtocol2(SECRET, SLAVE_NAME);
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

        mockStatic(EngineUtil.class);
        when(EngineUtil.readLine(inputStream)).thenReturn("error");

        assertEquals("error", protocol.performHandshake(outputStream, inputStream));

        verify(outputStream).writeUTF("Protocol:JNLP2-connect");
        verify(outputStream).writeUTF(argThat(matchesPropertiesOutput(expectedProperties)));
    }

    @Test
    public void testHandshakeSuccess() throws Exception {
        // Properties slave sends to master.
        Properties expectedProperties = new Properties();
        expectedProperties.put(JnlpProtocol2.SECRET_KEY, SECRET);
        expectedProperties.put(JnlpProtocol2.SLAVE_NAME_KEY, SLAVE_NAME);
        // Properties master sends back.
        Properties responseProperties = new Properties();
        responseProperties.put(JnlpProtocol2.COOKIE_KEY, COOKIE);

        mockStatic(EngineUtil.class);
        when(EngineUtil.readLine(inputStream)).thenReturn(JnlpProtocol.GREETING_SUCCESS);
        when(EngineUtil.readResponseHeaders(inputStream)).thenReturn(responseProperties);

        assertEquals(JnlpProtocol.GREETING_SUCCESS, protocol.performHandshake(outputStream, inputStream));
        assertEquals(COOKIE, protocol.getCookie());

        verify(outputStream).writeUTF("Protocol:JNLP2-connect");
        verify(outputStream).writeUTF(argThat(matchesPropertiesOutput(expectedProperties)));
    }

    @Test
    public void testRepeatedHandshakeSendsCookie() throws Exception {
        // Properties slave sends to master the first time.
        Properties expectedProperties = new Properties();
        expectedProperties.put(JnlpProtocol2.SECRET_KEY, SECRET);
        expectedProperties.put(JnlpProtocol2.SLAVE_NAME_KEY, SLAVE_NAME);
        // Properties master sends back first time.
        Properties responseProperties = new Properties();
        responseProperties.put(JnlpProtocol2.COOKIE_KEY, COOKIE);
        // Properties slave sends to master the second time.
        Properties expectedProperties2 = new Properties();
        expectedProperties2.put(JnlpProtocol2.SECRET_KEY, SECRET);
        expectedProperties2.put(JnlpProtocol2.SLAVE_NAME_KEY, SLAVE_NAME);
        expectedProperties2.put(JnlpProtocol2.COOKIE_KEY, COOKIE);
        // Properties master sends back second time.
        Properties responseProperties2 = new Properties();
        responseProperties2.put(JnlpProtocol2.COOKIE_KEY, COOKIE2);

        mockStatic(EngineUtil.class);
        InOrder order = inOrder(outputStream);
        when(EngineUtil.readLine(inputStream)).thenReturn(JnlpProtocol.GREETING_SUCCESS);
        when(EngineUtil.readResponseHeaders(inputStream)).thenReturn(responseProperties)
                .thenReturn(responseProperties2);

        assertEquals(JnlpProtocol.GREETING_SUCCESS, protocol.performHandshake(outputStream, inputStream));
        assertEquals(COOKIE, protocol.getCookie());
        assertEquals(JnlpProtocol.GREETING_SUCCESS, protocol.performHandshake(outputStream, inputStream));
        assertEquals(COOKIE2, protocol.getCookie());

        order.verify(outputStream).writeUTF("Protocol:JNLP2-connect");
        order.verify(outputStream).writeUTF(argThat(matchesPropertiesOutput(expectedProperties)));
        order.verify(outputStream).writeUTF("Protocol:JNLP2-connect");
        order.verify(outputStream).writeUTF(argThat(matchesPropertiesOutput(expectedProperties2)));
    }

    @Test
    public void testPropertiesStringMatcherSuccess() throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        Properties properties = new Properties();
        properties.put("foo", "bar");

        properties.store(stream, null);
        String str1 = stream.toString("UTF-8");

        // Sleep for a little over a second to ensure the comments at the top
        // of the string representation contain different timestamps
        Thread.sleep(1250);

        stream.reset();
        properties.store(stream, null);
        String str2 = stream.toString("UTF-8");

        assertNotEquals(str1, str2);
        assertTrue(matchesPropertiesOutput(properties).matches(str1));
        assertTrue(matchesPropertiesOutput(properties).matches(str2));
    }

    @Test
    public void testPropertiesStringMatcherFailure() throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        Properties properties = new Properties();
        properties.put("foo", "bar");
        properties.store(stream, null);
        String str = stream.toString("UTF-8");
        properties.put("bar", "baz");

        assertFalse(matchesPropertiesOutput(properties).matches(str));
    }

    /**
     * Properties.store() writes a comment with a datestamp, so this creates a Matcher which will
     * actually parse the stored value and confirm the resulting Properties objects match.
     */
    private static ArgumentMatcher<String> matchesPropertiesOutput(Properties properties) {
        final Properties expected = (Properties) properties.clone();
        return new ArgumentMatcher<String>() {
            @Override
            public boolean matches(Object obj) {
                StringBufferInputStream stream = new StringBufferInputStream((String) obj);
                Properties actual = new Properties();
                try {
                    actual.load(stream);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                return expected.equals(actual);
            }
        };
    }
}
