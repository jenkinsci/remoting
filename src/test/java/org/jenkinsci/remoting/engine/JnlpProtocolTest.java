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

import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.powermock.api.mockito.PowerMockito.when;
import static org.powermock.api.mockito.PowerMockito.whenNew;

/**
 * Tests for {@link JnlpProtocol}.
 *
 * @author Akshay Dayal
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(JnlpProtocol.class)
public class JnlpProtocolTest {

    @Mock private Socket mockSocket;
    @Mock private ChannelBuilder mockChannelBuilder;
    @Mock private Channel mockChannel;
    @Mock private OutputStream mockOutputStream;
    @Mock private InputStream mockInputStream;
    @Mock private DataOutputStream mockDataOutputStream;
    @Mock private BufferedInputStream mockBufferedInputStream;
    @Mock private JnlpProtocol mockProtocol;

    @Before
    public void setUp() throws Exception {
        when(mockSocket.getOutputStream()).thenReturn(mockOutputStream);
        when(mockSocket.getInputStream()).thenReturn(mockInputStream);
        whenNew(DataOutputStream.class).withArguments(mockOutputStream).thenReturn(mockDataOutputStream);
        whenNew(BufferedInputStream.class).withArguments(mockInputStream).thenReturn(mockBufferedInputStream);
        when(mockProtocol.establishChannel(mockSocket, mockChannelBuilder)).thenCallRealMethod();
    }

    @Test
    public void testHandshakeFails() throws Exception {
        when(mockProtocol.performHandshake(mockDataOutputStream, mockBufferedInputStream)).thenReturn(false);

        assertNull(mockProtocol.establishChannel(mockSocket, mockChannelBuilder));
    }

    @Test
    public void testHandshakeSucceeds() throws Exception {
        when(mockProtocol.performHandshake(mockDataOutputStream, mockBufferedInputStream)).thenReturn(true);
        when(mockProtocol.buildChannel(mockSocket, mockChannelBuilder, null)).thenReturn(mockChannel);

        assertSame(mockChannel, mockProtocol.establishChannel(mockSocket, mockChannelBuilder));
    }
}
