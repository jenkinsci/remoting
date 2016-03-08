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
import hudson.remoting.EngineListener;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.when;
import static org.powermock.api.mockito.PowerMockito.whenNew;

/**
 * Tests for {@link JnlpProtocol3}.
 *
 * @author Akshay Dayal
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({EngineUtil.class, Jnlp3Util.class, JnlpProtocol3.class})
@PowerMockIgnore({"javax.crypto.*", "javax.crypto.spec.*"})
public class JnlpProtocol3Test {

    private static final String SECRET = "secret";
    private static final String SLAVE_NAME = "slave-name";
    private static final String COOKIE = "some-cookie";
    private static final String COOKIE2 = "some-other-cookie";

    @Mock private Socket mockSocket;
    @Mock private ChannelBuilder mockChannelBuilder;
    @Mock private Channel mockChannel;
    @Mock private OutputStream mockOutputStream;
    @Mock private InputStream mockInputStream;
    @Mock private DataOutputStream mockDataOutputStream;
    @Mock private BufferedInputStream mockBufferedInputStream;
    @Mock private EngineListener mockEvents;
    @Mock private CipherOutputStream mockCipherOutputStream;
    @Mock private CipherInputStream mockCipherInputStream;
    private JnlpProtocol3 protocol;
    private HandshakeCiphers handshakeCiphers;
    private InOrder inOrder;

    @Before
    public void setUp() throws Exception {
        protocol = new JnlpProtocol3(SLAVE_NAME, SECRET, mockEvents);
        handshakeCiphers = HandshakeCiphers.create(SLAVE_NAME, SECRET);
        inOrder = inOrder(mockDataOutputStream);
        mockStatic(EngineUtil.class);
        mockStatic(Jnlp3Util.class);
        when(Jnlp3Util.generate128BitKey(Mockito.anyString())).thenCallRealMethod();
        when(Jnlp3Util.generate128BitKey()).thenCallRealMethod();
    }

    @Test
    public void testGetName() {
        assertEquals("JNLP3-connect", protocol.getName());
    }

    @Test
    public void testIncorrectChallengeResponseFromMaster() throws Exception {
        Properties expectedProperties = new Properties();
        expectedProperties.put(JnlpProtocol3.SLAVE_NAME_KEY, SLAVE_NAME);
        expectedProperties.put(JnlpProtocol3.CHALLENGE_KEY, handshakeCiphers.encrypt("challenge"));
        ByteArrayOutputStream expectedPropertiesStream = new ByteArrayOutputStream();
        expectedProperties.store(expectedPropertiesStream, null);

        when(Jnlp3Util.generateChallenge()).thenReturn("challenge");
        when(EngineUtil.readLine(mockBufferedInputStream)).thenReturn("10");
        when(EngineUtil.readChars(mockBufferedInputStream, 10))
                .thenReturn(handshakeCiphers.encrypt("response"));
        when(Jnlp3Util.validateChallengeResponse("challenge", "response")).thenReturn(false);

        assertFalse(protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP3-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(argThat(
                new PropertiesStringMatcher(expectedPropertiesStream.toString("UTF-8"))));
    }

    @Test
    public void testMasterRejectsSlave() throws Exception {
        Properties expectedProperties = new Properties();
        expectedProperties.put(JnlpProtocol3.SLAVE_NAME_KEY, SLAVE_NAME);
        expectedProperties.put(JnlpProtocol3.CHALLENGE_KEY, handshakeCiphers.encrypt("challenge"));
        ByteArrayOutputStream expectedPropertiesStream = new ByteArrayOutputStream();
        expectedProperties.store(expectedPropertiesStream, null);

        when(Jnlp3Util.generateChallenge()).thenReturn("challenge");
        when(EngineUtil.readLine(mockBufferedInputStream))
                .thenReturn(JnlpProtocol3.NEGOTIATE_LINE)
                .thenReturn("10")
                .thenReturn("15")
                .thenReturn("error");
        when(EngineUtil.readChars(mockBufferedInputStream, 10))
                .thenReturn(handshakeCiphers.encrypt("response"));
        when(Jnlp3Util.validateChallengeResponse("challenge", "response")).thenReturn(true);
        when(EngineUtil.readChars(mockBufferedInputStream, 15))
                .thenReturn(handshakeCiphers.encrypt("masterChallenge"));
        when(Jnlp3Util.createChallengeResponse("masterChallenge")).thenReturn("slaveResponse");

        assertFalse(protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP3-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(argThat(
                new PropertiesStringMatcher(expectedPropertiesStream.toString("UTF-8"))));
        inOrder.verify(mockDataOutputStream).writeUTF(JnlpProtocol.GREETING_SUCCESS);
        inOrder.verify(mockDataOutputStream).writeUTF(handshakeCiphers.encrypt("slaveResponse"));
    }

    @Test
    public void testSuccessfulHandshake() throws Exception {
        Properties expectedProperties = new Properties();
        expectedProperties.put(JnlpProtocol3.SLAVE_NAME_KEY, SLAVE_NAME);
        expectedProperties.put(JnlpProtocol3.CHALLENGE_KEY, handshakeCiphers.encrypt("challenge"));
        ByteArrayOutputStream expectedPropertiesStream = new ByteArrayOutputStream();
        expectedProperties.store(expectedPropertiesStream, null);

        when(Jnlp3Util.generateChallenge()).thenReturn("challenge");
        when(EngineUtil.readLine(mockBufferedInputStream))
                .thenReturn(JnlpProtocol3.NEGOTIATE_LINE)
                .thenReturn("10")
                .thenReturn("15")
                .thenReturn(JnlpProtocol.GREETING_SUCCESS)
                .thenReturn(handshakeCiphers.encrypt(COOKIE));
        when(EngineUtil.readChars(mockBufferedInputStream, 10))
                .thenReturn(handshakeCiphers.encrypt("response"));
        when(Jnlp3Util.validateChallengeResponse("challenge", "response")).thenReturn(true);
        when(EngineUtil.readChars(mockBufferedInputStream, 15))
                .thenReturn(handshakeCiphers.encrypt("masterChallenge"));
        when(Jnlp3Util.createChallengeResponse("masterChallenge")).thenReturn("slaveResponse");
        when(Jnlp3Util.keyToString(any(byte[].class)))
                .thenReturn("aesKey")
                .thenReturn("specKey");

        assertTrue(protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));
        assertEquals(COOKIE, protocol.getCookie());

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP3-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(argThat(
                new PropertiesStringMatcher(expectedPropertiesStream.toString("UTF-8"))));
        inOrder.verify(mockDataOutputStream).writeUTF(JnlpProtocol.GREETING_SUCCESS);
        inOrder.verify(mockDataOutputStream).writeUTF(handshakeCiphers.encrypt("slaveResponse"));
        inOrder.verify(mockDataOutputStream).writeUTF(handshakeCiphers.encrypt("aesKey"));
        inOrder.verify(mockDataOutputStream).writeUTF(handshakeCiphers.encrypt("specKey"));
    }

    @Test
    public void testRepeatedHandshakeSendsCookie() throws Exception {
        // Properties slave sends the first time.
        Properties expectedProperties1 = new Properties();
        expectedProperties1.put(JnlpProtocol3.SLAVE_NAME_KEY, SLAVE_NAME);
        expectedProperties1.put(JnlpProtocol3.CHALLENGE_KEY, handshakeCiphers.encrypt("challenge1"));
        ByteArrayOutputStream expectedPropertiesStream1 = new ByteArrayOutputStream();
        expectedProperties1.store(expectedPropertiesStream1, null);

        // Properties slave sends the second time.
        Properties expectedProperties2 = new Properties();
        expectedProperties2.put(JnlpProtocol3.SLAVE_NAME_KEY, SLAVE_NAME);
        expectedProperties2.put(JnlpProtocol3.CHALLENGE_KEY, handshakeCiphers.encrypt("challenge2"));
        expectedProperties2.put(JnlpProtocol3.COOKIE_KEY, handshakeCiphers.encrypt(COOKIE));
        ByteArrayOutputStream expectedPropertiesStream2 = new ByteArrayOutputStream();
        expectedProperties2.store(expectedPropertiesStream2, null);

        when(Jnlp3Util.generateChallenge())
                .thenReturn("challenge1")
                .thenReturn("challenge2");
        when(EngineUtil.readLine(mockBufferedInputStream))
                .thenReturn(JnlpProtocol3.NEGOTIATE_LINE)
                .thenReturn("10")
                .thenReturn("15")
                .thenReturn(JnlpProtocol.GREETING_SUCCESS)
                .thenReturn(handshakeCiphers.encrypt(COOKIE))
                .thenReturn(JnlpProtocol3.NEGOTIATE_LINE)
                .thenReturn("20")
                .thenReturn("25")
                .thenReturn(JnlpProtocol.GREETING_SUCCESS)
                .thenReturn(handshakeCiphers.encrypt(COOKIE2));
        when(EngineUtil.readChars(mockBufferedInputStream, 10))
                .thenReturn(handshakeCiphers.encrypt("response1"));
        when(Jnlp3Util.validateChallengeResponse("challenge1", "response1")).thenReturn(true);
        when(EngineUtil.readChars(mockBufferedInputStream, 15)).thenReturn(
                handshakeCiphers.encrypt("masterChallenge"));
        when(Jnlp3Util.createChallengeResponse("masterChallenge")).thenReturn("slaveResponse");
        when(Jnlp3Util.keyToString(any(byte[].class)))
                .thenReturn("aesKey")
                .thenReturn("specKey")
                .thenReturn("aesKey2")
                .thenReturn("specKey2");
        when(EngineUtil.readChars(mockBufferedInputStream, 20))
                .thenReturn(handshakeCiphers.encrypt("response2"));
        when(Jnlp3Util.validateChallengeResponse("challenge2", "response2")).thenReturn(true);
        when(EngineUtil.readChars(mockBufferedInputStream, 25)).thenReturn(
                handshakeCiphers.encrypt("masterChallenge2"));
        when(Jnlp3Util.createChallengeResponse("masterChallenge2")).thenReturn("slaveResponse2");

        assertTrue(protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));
        assertTrue(protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream));
        assertEquals(COOKIE2, protocol.getCookie());

        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP3-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(argThat(
                new PropertiesStringMatcher(expectedPropertiesStream1.toString("UTF-8"))));
        inOrder.verify(mockDataOutputStream).writeUTF(JnlpProtocol.GREETING_SUCCESS);
        inOrder.verify(mockDataOutputStream).writeUTF(handshakeCiphers.encrypt("slaveResponse"));
        inOrder.verify(mockDataOutputStream).writeUTF(handshakeCiphers.encrypt("aesKey"));
        inOrder.verify(mockDataOutputStream).writeUTF(handshakeCiphers.encrypt("specKey"));
        inOrder.verify(mockDataOutputStream).writeUTF("Protocol:JNLP3-connect");
        inOrder.verify(mockDataOutputStream).writeUTF(argThat(
                new PropertiesStringMatcher(expectedPropertiesStream2.toString("UTF-8"))));
        inOrder.verify(mockDataOutputStream).writeUTF(JnlpProtocol.GREETING_SUCCESS);
        inOrder.verify(mockDataOutputStream).writeUTF(handshakeCiphers.encrypt("slaveResponse2"));
        inOrder.verify(mockDataOutputStream).writeUTF(handshakeCiphers.encrypt("aesKey2"));
        inOrder.verify(mockDataOutputStream).writeUTF(handshakeCiphers.encrypt("specKey2"));
    }

    @Test
    public void testBuildChannel() throws Exception {
        when(Jnlp3Util.generateChallenge()).thenReturn("challenge");
        when(EngineUtil.readLine(mockBufferedInputStream))
                .thenReturn(JnlpProtocol3.NEGOTIATE_LINE)
                .thenReturn("10")
                .thenReturn("15")
                .thenReturn(JnlpProtocol.GREETING_SUCCESS)
                .thenReturn(handshakeCiphers.encrypt(COOKIE));
        when(EngineUtil.readChars(mockBufferedInputStream, 10))
                .thenReturn(handshakeCiphers.encrypt("response"));
        when(Jnlp3Util.validateChallengeResponse("challenge", "response")).thenReturn(true);
        when(EngineUtil.readChars(mockBufferedInputStream, 15))
                .thenReturn(handshakeCiphers.encrypt("masterChallenge"));
        when(Jnlp3Util.createChallengeResponse("masterChallenge")).thenReturn("slaveResponse");
        when(Jnlp3Util.keyToString(any(byte[].class)))
                .thenReturn("aesKey")
                .thenReturn("specKey");

        // Perform a handshake so the channel ciphers get created.
        protocol.performHandshake(mockDataOutputStream, mockBufferedInputStream);

        when(mockSocket.getOutputStream()).thenReturn(mockOutputStream);
        when(mockSocket.getInputStream()).thenReturn(mockInputStream);
        whenNew(CipherOutputStream.class)
                .withArguments(mockOutputStream, protocol.getChannelCiphers().getEncryptCipher())
                .thenReturn(mockCipherOutputStream);
        whenNew(CipherInputStream.class)
                .withArguments(mockBufferedInputStream, protocol.getChannelCiphers().getDecryptCipher())
                .thenReturn(mockCipherInputStream);
        when(mockChannelBuilder.build(mockCipherInputStream, mockCipherOutputStream))
                .thenReturn(mockChannel);

        assertSame(mockChannel, protocol.buildChannel(mockSocket, mockChannelBuilder, mockBufferedInputStream));
    }
}
