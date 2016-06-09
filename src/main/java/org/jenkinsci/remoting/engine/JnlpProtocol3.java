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

import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Properties;

import static org.jenkinsci.remoting.engine.EngineUtil.*;
import static org.jenkinsci.remoting.engine.Jnlp3Util.*;

/**
 * Implementation of the JNLP3-connect protocol.
 *
 * <p>This protocol aims to provide a basic level of security for JNLP based
 * slaves. Both the master and the slave securely authenticate each other and
 * then setup an encrypted {@link Channel}.
 *
 * <p>The slave secret is never exchanged, but instead used as a shared secret
 * to generate matching symmetric key {@link javax.crypto.Cipher}s by both
 * sides which are used to perform a secure handshake. During the handshake
 * both the slave and the master send each other challenge phrases which can
 * only be decrypted with the matching cipher created with the slave secret.
 * Once decrypted the SHA-256 hash of the challenge is computed and sent back
 * to authenticate.
 *
 * <p>Once the handshake is successful another pair of symmetric key ciphers
 * are created by the slave using random keys. These are then shared with the
 * master. These ciphers are used to create an encrypted channel by both sides.
 *
 * <p>The following goes over the handshake in more detail:
 * <pre>
 * Client                                                                Master
 *           handshake ciphers = createFrom(slave name, slave secret)
 *
 *   |                                                                     |
 *   |      initiate(slave name, encrypt(challenge), encrypt(cookie))      |
 *   |  -------------------------------------------------------------->>>  |
 *   |                                                                     |
 *   |                       encrypt(hash(challenge))                      |
 *   |  <<<--------------------------------------------------------------  |
 *   |                                                                     |
 *   |                          GREETING_SUCCESS                           |
 *   |  -------------------------------------------------------------->>>  |
 *   |                                                                     |
 *   |                         encrypt(challenge)                          |
 *   |  <<<--------------------------------------------------------------  |
 *   |                                                                     |
 *   |                       encrypt(hash(challenge))                      |
 *   |  -------------------------------------------------------------->>>  |
 *   |                                                                     |
 *   |                          GREETING_SUCCESS                           |
 *   |  <<<--------------------------------------------------------------  |
 *   |                                                                     |
 *   |                          encrypt(cookie)                            |
 *   |  <<<--------------------------------------------------------------  |
 *   |                                                                     |
 *   |                  encrypt(AES key) + encrypt(IvSpec)                 |
 *   |  -------------------------------------------------------------->>>  |
 *   |                                                                     |
 *
 *              channel ciphers = createFrom(AES key, IvSpec)
 *           channel = channelBuilder.createWith(channel ciphers)
 * </pre>
 *
 * <p>The entire process assumes the slave secret has not been leaked
 * beforehand and the slave obtains it in a secure manner.
 *
 * <p>The key sizes are only 128bit since it cannot be assumed everyone has
 * the <a href="http://www.oracle.com/technetwork/java/javase/downloads/jce-6-download-429243.html">
 * Java Cryptography Extension</a> available. In the future maybe the key
 * size could be made a parameter or the implementation can check to see if
 * 256bit sizes are supported.
 *
 * @author Akshay Dayal
 * @see JnlpServer3Handshake
 */
class JnlpProtocol3 extends JnlpProtocol {

    /**
     * This cookie identifies the current connection, allowing us to force the
     * server to drop the client if we initiate a reconnection from our end
     * (even when the server still thinks the connection is alive.)
     */
    private String cookie;
    private ChannelCiphers channelCiphers;

    JnlpProtocol3(String slaveName, String slaveSecret, EngineListener events) {
        super(slaveName, slaveSecret, events);
    }

    @Override
    public String getName() {
        return NAME;
    }

    String getCookie() {
        return cookie;
    }

    ChannelCiphers getChannelCiphers() {
        return channelCiphers;
    }

    @Override
    boolean performHandshake(DataOutputStream outputStream,
            BufferedInputStream inputStream) throws IOException {
        HandshakeCiphers handshakeCiphers = HandshakeCiphers.create(slaveName, slaveSecret);

        // Authenticate the master.
        if (!initiateAndValidateMaster(inputStream, outputStream, handshakeCiphers)) {
            return false;
        }

        // The master now wants to authenticate us.
        if (!authenticateToMaster(inputStream, outputStream, handshakeCiphers)) {
            return false;
        }

        // Authentication complete, send encryption keys to use for Channel.
        channelCiphers = ChannelCiphers.create();
        outputStream.writeUTF(handshakeCiphers.encrypt(
                Jnlp3Util.keyToString(channelCiphers.getAesKey())));
        outputStream.writeUTF(handshakeCiphers.encrypt(
                Jnlp3Util.keyToString(channelCiphers.getSpecKey())));

        return true;
    }

    @Override
    Channel buildChannel(Socket socket, ChannelBuilder channelBuilder, BufferedInputStream inputStream) throws IOException {
        return channelBuilder.build(
                new CipherInputStream(inputStream, channelCiphers.getDecryptCipher()),
                new CipherOutputStream(socket.getOutputStream(), channelCiphers.getEncryptCipher())
        );
    }

    private boolean initiateAndValidateMaster(BufferedInputStream inputStream,
            DataOutputStream outputStream, HandshakeCiphers handshakeCiphers) throws IOException {
        String challenge = Jnlp3Util.generateChallenge();

        // Send initial information which includes the encrypted challenge.
        Properties props = new Properties();
        props.put(SLAVE_NAME_KEY, slaveName);
        props.put(CHALLENGE_KEY, handshakeCiphers.encrypt(challenge));
        if (cookie != null) {
            props.put(COOKIE_KEY, handshakeCiphers.encrypt(cookie));
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        props.store(o, null);
        outputStream.writeUTF(PROTOCOL_PREFIX + NAME);
        outputStream.writeUTF(o.toString("UTF-8"));

        String protocolUnderstoodResponse = readLine(inputStream);
        if (!protocolUnderstoodResponse.equals(NEGOTIATE_LINE)) {
            events.status("Server didn't accept the handshake: " + protocolUnderstoodResponse);
            return false;   // the server didn't understand the protocol
        }

        // Validate challenge response.
        Integer challengeResponseLength = Integer.parseInt(readLine(inputStream));
        String encryptedChallengeResponse = readChars(inputStream, challengeResponseLength);
        String challengeResponse = handshakeCiphers.decrypt(encryptedChallengeResponse);
        if (!Jnlp3Util.validateChallengeResponse(challenge, challengeResponse)) {
            events.status(NAME + ": Incorrect challenge response from master");
            return false;
        }

        outputStream.writeUTF(GREETING_SUCCESS);
        return true;
    }

    private boolean authenticateToMaster(BufferedInputStream inputStream,
            DataOutputStream outputStream, HandshakeCiphers handshakeCiphers) throws IOException {
        // Read the master challenge.
        Integer challengeLength = Integer.parseInt(readLine(inputStream));
        String encryptedChallenge = readChars(inputStream, challengeLength);
        String masterChallenge = handshakeCiphers.decrypt(encryptedChallenge);

        // Send the response.
        String challengeResponse = createChallengeResponse(masterChallenge);
        String encryptedChallengeResponse = handshakeCiphers.encrypt(challengeResponse);
        outputStream.writeUTF(encryptedChallengeResponse);

        // See if the master accepted us.
        if (!GREETING_SUCCESS.equals(readLine(inputStream))) {
            return false;
        }
        cookie = handshakeCiphers.decrypt(readLine(inputStream));
        return true;
    }

    public static final String CHALLENGE_KEY = "Challenge";
    public static final String COOKIE_KEY = "Cookie";
    public static final String NAME = "JNLP3-connect";
    public static final String SLAVE_NAME_KEY = "Node-Name";

    /**
     * If we talk to the server who doesn't understand this protocol, it sends out
     * an error message line, so to distinguish those we need another line that
     * indicates the protocol is understood by the server.
     */
    /*package*/ static final String NEGOTIATE_LINE = "Negotiate";
}
