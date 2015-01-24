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
package hudson.remoting.engine;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;

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

    private JnlpProtocol1 protocol;
    @Mock private DataOutputStream outputStream;
    @Mock private BufferedInputStream inputStream;

    @Before
    public void setUp() throws Exception {
        protocol = new JnlpProtocol1(SECRET, SLAVE_NAME);
    }

    @Test
    public void testGetName() {
        assertEquals("JNLP-connect", protocol.getName());
    }

    @Test
    public void testPerformHandshake() throws Exception {
        mockStatic(EngineUtil.class);
        when(EngineUtil.readLine(inputStream)).thenReturn("response");

        assertEquals("response", protocol.performHandshake(outputStream, inputStream));

        verify(outputStream).writeUTF("Protocol:JNLP-connect");
        verify(outputStream).writeUTF(SECRET);
        verify(outputStream).writeUTF(SLAVE_NAME);
    }
}
