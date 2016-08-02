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
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import org.jenkinsci.remoting.nio.NioChannelHub;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;
import org.jenkinsci.remoting.util.Charsets;

import static org.jenkinsci.remoting.engine.EngineUtil.readChars;
import static org.jenkinsci.remoting.engine.EngineUtil.readLine;
import static org.jenkinsci.remoting.engine.Jnlp3Util.createChallengeResponse;

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
 */
@Deprecated
public class JnlpProtocol3Handler extends LegacyJnlpProtocolHandler<Jnlp3ConnectionState> {

    private static final Random RANDOM = new SecureRandom();
    static final String COOKIE_NAME = "org.jenkinsci.remoting.engine.JnlpProtocol3.cookie";
    public static final String CHALLENGE_KEY = "Challenge";

    /**
     * If we talk to the server who doesn't understand this protocol, it sends out
     * an error message line, so to distinguish those we need another line that
     * indicates the protocol is understood by the server.
     */
    /*package*/ static final String NEGOTIATE_LINE = "Negotiate";
    static final String NAME = "JNLP3-connect";

    private final SecretDatabase secretDatabase;

    public JnlpProtocol3Handler(@Nonnull ExecutorService threadPool, @Nullable NioChannelHub hub,
                                @Nullable SecretDatabase secretDatabase) {
        super(threadPool, hub);
        this.secretDatabase = secretDatabase;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Jnlp3ConnectionState createConnectionState(Socket socket, List<JnlpConnectionStateListener> listeners)
            throws IOException {
        return new Jnlp3ConnectionState(socket, listeners);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void sendHandshake(Jnlp3ConnectionState state, Map<String, String> headers)
            throws IOException {
        String secretKey = headers.get(JnlpConnectionState.SECRET_KEY);
        if (secretKey == null) {
            throw new ConnectionRefusalException("Client headers missing " + JnlpConnectionState.SECRET_KEY);
        }
        String clientName = headers.get(JnlpConnectionState.CLIENT_NAME_KEY);
        if (clientName == null) {
            throw new ConnectionRefusalException("Client headers missing " + JnlpConnectionState.CLIENT_NAME_KEY);
        }
        String cookie = headers.get(JnlpConnectionState.COOKIE_KEY);
        HandshakeCiphers handshakeCiphers = HandshakeCiphers.create(clientName, secretKey);

        // Authenticate the master.
        String challenge = Jnlp3Util.generateChallenge();

        // Send initial information which includes the encrypted challenge.
        Properties props = new Properties();
        props.put(JnlpConnectionState.CLIENT_NAME_KEY, clientName);
        props.put(CHALLENGE_KEY, handshakeCiphers.encrypt(challenge));
        if (cookie != null) {
            props.put(JnlpConnectionState.COOKIE_KEY, handshakeCiphers.encrypt(cookie));
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        props.store(o, null);
        state.fireBeforeProperties();
        DataOutputStream outputStream = state.getDataOutputStream();
        outputStream.writeUTF(PROTOCOL_PREFIX + NAME);
        outputStream.writeUTF(o.toString("UTF-8"));
        outputStream.flush();

        BufferedInputStream inputStream = state.getBufferedInputStream();
        String protocolUnderstoodResponse = readLine(inputStream);
        if (!protocolUnderstoodResponse.equals(NEGOTIATE_LINE)) {
            throw new ConnectionRefusalException("Server didn't accept the handshake: " + protocolUnderstoodResponse);
        }

        // Validate challenge response.
        Integer challengeResponseLength = Integer.parseInt(readLine(inputStream));
        String encryptedChallengeResponse = readChars(inputStream, challengeResponseLength);
        String challengeResponse = handshakeCiphers.decrypt(encryptedChallengeResponse);
        if (!Jnlp3Util.validateChallengeResponse(challenge, challengeResponse)) {
            throw new ConnectionRefusalException(NAME + ": Incorrect challenge response from master");
        }

        outputStream.writeUTF(GREETING_SUCCESS);
        outputStream.flush();

        // The master now wants to authenticate us.
        // Read the master challenge.
        Integer challengeLength = Integer.parseInt(readLine(inputStream));
        String encryptedChallenge = readChars(inputStream, challengeLength);
        String masterChallenge = handshakeCiphers.decrypt(encryptedChallenge);

        // Send the response.
        challengeResponse = createChallengeResponse(masterChallenge);
        encryptedChallengeResponse = handshakeCiphers.encrypt(challengeResponse);
        outputStream.writeUTF(encryptedChallengeResponse);
        outputStream.flush();

        // See if the master accepted us.
        String masterResponse = readLine(inputStream);
        if (!GREETING_SUCCESS.equals(masterResponse)) {
            throw new ConnectionRefusalException(NAME + ": Master rejected connection: " + masterResponse);
        }

        // Authentication complete, send encryption keys to use for Channel.
        ChannelCiphers channelCiphers = ChannelCiphers.create();
        outputStream.writeUTF(handshakeCiphers.encrypt(
                Jnlp3Util.keyToString(channelCiphers.getAesKey())));
        outputStream.writeUTF(handshakeCiphers.encrypt(
                Jnlp3Util.keyToString(channelCiphers.getSpecKey())));
        outputStream.flush();
        state.setChannelCiphers(channelCiphers);

        cookie = handshakeCiphers.decrypt(readLine(inputStream));
        Map<String, String> properties = new HashMap<String, String>();
        properties.put(JnlpConnectionState.COOKIE_KEY, cookie);
        state.fireAfterProperties(properties);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    void receiveHandshake(Jnlp3ConnectionState state, Map<String, String> headers) throws IOException {
        PrintWriter out = state.getPrintWriter();
        out.println(NEGOTIATE_LINE);

        // Get initiation information from slave.
        Properties request = new Properties();
        DataInputStream in = state.getDataInputStream();
        request.load(new ByteArrayInputStream(in.readUTF().getBytes(Charsets.UTF_8)));
        String clientName = request.getProperty(JnlpConnectionState.CLIENT_NAME_KEY);
        String secretKey = secretDatabase == null ? null : secretDatabase.lookup(clientName);
        if (secretKey == null) {
            throw new ConnectionRefusalException("Unknown client name: " + clientName);
        }
        HandshakeCiphers handshakeCiphers = HandshakeCiphers.create(clientName, secretKey);

        String challenge = handshakeCiphers.decrypt(
                request.getProperty(CHALLENGE_KEY));

        // Send agent challenge response.
        String challengeResponse = Jnlp3Util.createChallengeResponse(challenge);
        String encryptedChallengeResponse = handshakeCiphers.encrypt(challengeResponse);
        out.println(encryptedChallengeResponse.getBytes(Charsets.UTF_8).length);
        out.print(encryptedChallengeResponse);
        out.flush();

        // If the slave accepted our challenge response send our challenge.
        String challengeVerificationMessage = in.readUTF();
        if (!challengeVerificationMessage.equals(GREETING_SUCCESS)) {
            throw new ConnectionRefusalException("Agent did not accept our challenge response");
        }

        // If there is a cookie decrypt it.
        Map<String, String> properties = new HashMap<String, String>();
        properties.putAll((Map) request);
        if (properties.get(JnlpConnectionState.COOKIE_KEY) != null) {
            properties.put(JnlpConnectionState.COOKIE_KEY,
                    handshakeCiphers.decrypt(properties.get(JnlpConnectionState.COOKIE_KEY)));
        }

        // now validate the client
        String masterChallenge = Jnlp3Util.generateChallenge();
        String encryptedMasterChallenge = handshakeCiphers.encrypt(masterChallenge);
        out.println(encryptedMasterChallenge.getBytes(Charsets.UTF_8).length);
        out.print(encryptedMasterChallenge);
        out.flush();

        // Verify the challenge response from the agent.
        String encryptedMasterChallengeResponse = in.readUTF();
        String masterChallengeResponse = handshakeCiphers.decrypt(
                encryptedMasterChallengeResponse);
        if (!Jnlp3Util.validateChallengeResponse(masterChallenge, masterChallengeResponse)) {
            throw new ConnectionRefusalException("Incorrect master challenge response from agent");
        }
        state.fireAfterProperties(properties);
        // Send greeting and new cookie.
        out.println(GREETING_SUCCESS);
        String newCookie = generateCookie();
        state.setNewCookie(newCookie);
        out.println(handshakeCiphers.encrypt(newCookie));
        out.flush();

        // Now get the channel cipher information.
        String aesKeyString = handshakeCiphers.decrypt(in.readUTF());
        String specKeyString = handshakeCiphers.decrypt(in.readUTF());
        state.setChannelCiphers(ChannelCiphers.create(
                Jnlp3Util.keyFromString(aesKeyString),
                Jnlp3Util.keyFromString(specKeyString)));
    }

    private String generateCookie() {
        byte[] cookie = new byte[32];
        RANDOM.nextBytes(cookie);
        return toHexString(cookie);
    }

    @Nonnull
    private String toHexString(@Nonnull byte[] bytes) {
        StringBuilder buf = new StringBuilder();
        for (byte bb : bytes) {
            int b = bb & 0xFF;
            if (b < 16) {
                buf.append('0');
            }
            buf.append(Integer.toHexString(b));
        }
        return buf.toString();
    }

    @Override
    Channel buildChannel(Jnlp3ConnectionState state) throws IOException {
        ChannelBuilder channelBuilder = state.getChannelBuilder();
        String newCookie = state.getNewCookie();
        if (newCookie != null) {
            channelBuilder.withProperty(COOKIE_NAME, newCookie);
        }
        return channelBuilder.build(
                new CipherInputStream(state.getBufferedInputStream(), state.getChannelCiphers().getDecryptCipher()),
                new CipherOutputStream(state.getSocket().getOutputStream(),
                        state.getChannelCiphers().getEncryptCipher())
        );
    }

    public interface SecretDatabase {
        String lookup(String clientName);
    }
}
