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
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.when;
import static org.powermock.api.mockito.PowerMockito.whenNew;

/**
 * Tests for {@link JnlpProtocol1}.
 *
 * @author Akshay Dayal
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({JnlpProtocol1.class, EngineUtil.class})
public class JnlpProtocol1Test {

    private static final String SECRET = "secret";
    private static final String SLAVE_NAME = "slave-name";

    @Mock private Socket mockSocket;
    @Mock private ChannelBuilder mockChannelBuilder;
    @Mock private Channel mockChannel;
    @Mock private OutputStream mockOutputStream;
    @Mock private InputStream mockInputStream;
    @Mock private DataOutputStream mockDataOutputStream;
    @Mock private BufferedOutputStream mockBufferedOutputStream;
    @Mock private BufferedInputStream mockBufferedInputStream;
    @Mock private EngineListenerSplitter mockEvents;
    private JnlpProtocol1 protocol;
    private InOrder inOrder;

    @Before
    public void setUp() throws Exception {
        protocol = new JnlpProtocol1(SLAVE_NAME, SECRET, mockEvents);
        inOrder = inOrder(mockDataOutputStream);
    }

    @Test
    public void testGetName() {
        assertEquals("JNLP-connect", protocol.getName());
    }

    @Test
    public void testPerformHandshakeFails() throws Exception {
        mockStatic(EngineUtil.class);
        when(EngineUtil.readLine(mockBufferedInputStream)).thenReturn("bad-response");

        assertFalse(protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(SECRET);
        inOrder.verify(mockDataOutputStream).writeUTF(SLAVE_NAME);
    }

    @Test
    public void testPerformHandshakeSucceeds() throws Exception {
        mockStatic(EngineUtil.class);
        when(EngineUtil.readLine(mockBufferedInputStream)).thenReturn(JnlpProtocol.GREETING_SUCCESS);

        assertTrue(protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(SECRET);
        inOrder.verify(mockDataOutputStream).writeUTF(SLAVE_NAME);
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
