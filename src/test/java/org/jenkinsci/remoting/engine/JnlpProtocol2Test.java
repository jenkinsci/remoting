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

import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.EngineListenerSplitter;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Date;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
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
    private static final Date THE_DATE = new Date();

    @Mock private Socket mockSocket;
    @Mock private ChannelBuilder mockChannelBuilder;
    @Mock private Channel mockChannel;
    @Mock private OutputStream mockOutputStream;
    @Mock private InputStream mockInputStream;
    @Mock private DataOutputStream mockDataOutputStream;
    @Mock private BufferedOutputStream mockBufferedOutputStream;
    @Mock private BufferedInputStream mockBufferedInputStream;
    @Mock private EngineListenerSplitter mockEvents;
    private JnlpProtocol2 protocol;
    private InOrder inOrder;

    @Before
    public void setUp() throws Exception {
        protocol = new JnlpProtocol2(SLAVE_NAME, SECRET, mockEvents);
        inOrder = inOrder(mockDataOutputStream);
    }

    @Test
    public void testGetName() {
        assertEquals("JNLP2-connect", protocol.getName());
    }

    @Test
    public void testPerformHandshakeFails() throws Exception {
        // The date comment when writing properties to stream should be the
        // same in the scope of the tests.
        whenNew(Date.class).withNoArguments().thenReturn(THE_DATE);

        Properties expectedProperties = new Properties();
        expectedProperties.put(JnlpProtocol2.SECRET_KEY, SECRET);
        expectedProperties.put(JnlpProtocol2.SLAVE_NAME_KEY, SLAVE_NAME);
        ByteArrayOutputStream expectedPropertiesStream = new ByteArrayOutputStream();
        expectedProperties.store(expectedPropertiesStream, null);

        mockStatic(EngineUtil.class);
        when(EngineUtil.readLine(mockBufferedInputStream)).thenReturn("error");

        assertFalse(protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP2-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(expectedPropertiesStream.toString("UTF-8"));
    }

    @Test
    public void testPerformHandshakeSucceeds() throws Exception {
        // The date comment when writing properties to stream should be the
        // same in the scope of the tests.
        whenNew(Date.class).withNoArguments().thenReturn(THE_DATE);

        // Properties slave sends to master.
        Properties expectedProperties = new Properties();
        expectedProperties.put(JnlpProtocol2.SECRET_KEY, SECRET);
        expectedProperties.put(JnlpProtocol2.SLAVE_NAME_KEY, SLAVE_NAME);
        ByteArrayOutputStream expectedPropertiesStream = new ByteArrayOutputStream();
        expectedProperties.store(expectedPropertiesStream, null);
        // Properties master sends back.
        Properties responseProperties = new Properties();
        responseProperties.put(JnlpProtocol2.COOKIE_KEY, COOKIE);

        mockStatic(EngineUtil.class);
        when(EngineUtil.readLine(mockBufferedInputStream)).thenReturn(JnlpProtocol.GREETING_SUCCESS);
        when(EngineUtil.readResponseHeaders(mockBufferedInputStream)).thenReturn(responseProperties);

        assertTrue(protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));
        assertEquals(COOKIE, protocol.getCookie());

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP2-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(expectedPropertiesStream.toString("UTF-8"));
    }

    @Test
    public void testRepeatedHandshakeSendsCookie() throws Exception {
        // The date comment when writing properties to stream should be the
        // same in the scope of the tests.
        whenNew(Date.class).withNoArguments().thenReturn(THE_DATE);

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

        mockStatic(EngineUtil.class);
        when(EngineUtil.readLine(mockBufferedInputStream)).thenReturn(JnlpProtocol.GREETING_SUCCESS);
        when(EngineUtil.readResponseHeaders(mockBufferedInputStream)).thenReturn(responseProperties)
                .thenReturn(responseProperties2);

        assertTrue(protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));
        assertEquals(COOKIE, protocol.getCookie());
        assertTrue(protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));
        assertEquals(COOKIE2, protocol.getCookie());

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP2-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(expectedPropertiesStream.toString("UTF-8"));
        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP2-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(expectedPropertiesStream2.toString("UTF-8"));
    }

    @Test
    public void testBuildChannel() throws Exception {
        when(mockSocket.getOutputStream()).thenReturn(mockOutputStream);
        when(mockSocket.getInputStream()).thenReturn(mockInputStream);
        whenNew(BufferedOutputStream.class).withArguments(mockOutputStream).thenReturn(mockBufferedOutputStream);
        whenNew(BufferedInputStream.class).withArguments(mockInputStream).thenReturn(mockBufferedInputStream);
        when(mockChannelBuilder.build(mockBufferedInputStream, mockBufferedOutputStream)).thenReturn(mockChannel);

        assertSame(mockChannel, protocol.buildChannel(mockSocket, mockChannelBuilder));
    }
}
