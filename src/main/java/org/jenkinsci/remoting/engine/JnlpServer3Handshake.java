package org.jenkinsci.remoting.engine;

import hudson.remoting.Channel;
import hudson.remoting.SocketChannelStream;
import org.jenkinsci.remoting.nio.NioChannelHub;

import javax.annotation.Nonnull;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ExecutorService;

/**
 * Server-side handshaking logic for {@link JnlpProtocol3}.
 *
 * @author Akshay Dayal
 */
public abstract class JnlpServer3Handshake extends JnlpServerHandshake {
    /**
     * If the client sends a connection cookie, that value is stored here.
     */
    protected String cookie;

    private HandshakeCiphers handshakeCiphers;

    private String nodeName;

    public JnlpServer3Handshake(NioChannelHub hub, ExecutorService threadPool, Socket socket) throws IOException {
        super(hub,threadPool,socket);
    }

    /**
     * Gets the node name reported from the other side.
     */
    public String getNodeName() {
        return nodeName;
    }

    /**
     * Performs the handshake and establishes a channel.
     */
    public Channel connect() throws IOException, InterruptedException {
        try {
            out.println(JnlpProtocol3.NEGOTIATE_LINE);

            // Get initiation information from slave.
            request.load(new ByteArrayInputStream(in.readUTF().getBytes(Charset.forName("UTF-8"))));
            nodeName = request.getProperty(JnlpProtocol3.SLAVE_NAME_KEY);

            this.handshakeCiphers = HandshakeCiphers.create(nodeName, getNodeSecret(nodeName));

            authenticateToSlave();

            // If there is a cookie decrypt it.
            if (getRequestProperty(JnlpProtocol3.COOKIE_KEY) != null) {
                cookie = handshakeCiphers.decrypt(getRequestProperty(JnlpProtocol3.COOKIE_KEY));
            }

            validateSlave();
        } catch (Failure f) {
            error(f.getMessage());
            return null;
        }

        // Send greeting and new cookie.
        out.println(JnlpProtocol.GREETING_SUCCESS);
        String newCookie = generateCookie();
        out.println(handshakeCiphers.encrypt(newCookie));

        // Now get the channel cipher information.
        String aesKeyString = handshakeCiphers.decrypt(in.readUTF());
        String specKeyString = handshakeCiphers.decrypt(in.readUTF());
        ChannelCiphers channelCiphers = ChannelCiphers.create(
                Jnlp3Util.keyFromString(aesKeyString),
                Jnlp3Util.keyFromString(specKeyString));

        Channel channel = createChannelBuilder(nodeName).build(
                new CipherInputStream(SocketChannelStream.in(socket),
                        channelCiphers.getDecryptCipher()),
                new CipherOutputStream(SocketChannelStream.out(socket),
                        channelCiphers.getEncryptCipher()));

        channel.setProperty(COOKIE_NAME, newCookie);

        return channel;
    }

    /**
     * Given the node name declared by the client, determine the secret
     * used for authentication.
     *
     * @throws Failure
     *      if a fatal problem is found in determining the secret, to abort
     *      the handshake gracefully.
     */
    protected abstract String getNodeSecret(String nodeName) throws Failure;

    private void authenticateToSlave() throws IOException, Failure {
        String challenge = handshakeCiphers.decrypt(
                request.getProperty(JnlpProtocol3.CHALLENGE_KEY));

        // Send slave challenge response.
        String challengeResponse = Jnlp3Util.createChallengeResponse(challenge);
        String encryptedChallengeResponse = handshakeCiphers.encrypt(challengeResponse);
        out.println(encryptedChallengeResponse.getBytes(Charset.forName("UTF-8")).length);
        out.print(encryptedChallengeResponse);
        out.flush();

        // If the slave accepted our challenge response send our challenge.
        String challengeVerificationMessage = in.readUTF();
        if (!challengeVerificationMessage.equals(JnlpProtocol.GREETING_SUCCESS)) {
            throw new Failure("Slave did not accept our challenge response");
        }
    }

    protected void validateSlave() throws IOException, Failure {
        String masterChallenge = Jnlp3Util.generateChallenge();
        String encryptedMasterChallenge = handshakeCiphers.encrypt(masterChallenge);
        out.println(encryptedMasterChallenge.getBytes(Charset.forName("UTF-8")).length);
        out.print(encryptedMasterChallenge);
        out.flush();

        // Verify the challenge response from the slave.
        String encryptedMasterChallengeResponse = in.readUTF();
        String masterChallengeResponse = handshakeCiphers.decrypt(
                encryptedMasterChallengeResponse);
        if (!Jnlp3Util.validateChallengeResponse(masterChallenge, masterChallengeResponse)) {
            throw new Failure("Incorrect master challenge response from slave");
        }
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
            if (b < 16) buf.append('0');
            buf.append(Integer.toHexString(b));
        }
        return buf.toString();
    }

    /**
     * Indicates a graceful handshake failure.
     *
     * This exception can be thrown during the handshake to refuse the inbound client.
     */
    protected class Failure extends Exception {
        public Failure(String msg) {
            super(msg);
        }
    }

    static final String COOKIE_NAME = JnlpProtocol3.class.getName() + ".cookie";

    private static final Random RANDOM = new SecureRandom();
}
