package org.jenkinsci.remoting.engine;

import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.SocketChannelStream;
import org.jenkinsci.remoting.nio.NioChannelHub;

import javax.annotation.Nonnull;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ExecutorService;

/**
 * @author Akshay Dayal
 */
public class JnlpServer3Handshake extends JnlpServerHandshake {
    /**
     * If the client sends a connection cookie, that value is stored here.
     */
    protected String cookie;

    private final HandshakeCiphers handshakeCiphers;

    public JnlpServer3Handshake(NioChannelHub hub, ExecutorService threadPool, Socket socket,
                                String nodeName, String nodeSecret) throws IOException {
        super(hub,threadPool,socket);
        this.handshakeCiphers = HandshakeCiphers.create(nodeName, nodeSecret);
    }

    /**
     * Performs the handshake and establishes a channel.
     */
    public Channel connect(ChannelBuilder cb) throws IOException, InterruptedException {

        // Authenticate to the slave.
        if (!authenticateToSlave()) {
            return null;
        }

        // If there is a cookie decrypt it.
        if (getRequestProperty(JnlpProtocol3.COOKIE_KEY) != null) {
            cookie = handshakeCiphers.decrypt(getRequestProperty(JnlpProtocol3.COOKIE_KEY));
        }

        // Validate the slave.
        if (!validateSlave()) {
            return null;
        }

        // TODO: this is where the check for existing slave happens

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

        Channel channel = cb.build(
                new CipherInputStream(SocketChannelStream.in(socket),
                        channelCiphers.getDecryptCipher()),
                new CipherOutputStream(SocketChannelStream.out(socket),
                        channelCiphers.getEncryptCipher()));

        channel.setProperty(COOKIE_NAME, newCookie);

        return channel;
    }

    private boolean authenticateToSlave() throws IOException {
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
            error("Slave did not accept our challenge response");
            return false;
        }

        return true;
    }

    private boolean validateSlave() throws IOException {
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
            error("Incorrect master challenge response from slave");
            return false;
        }

        return true;
    }

    private String generateCookie() {
        byte[] cookie = new byte[32];
        RANDOM.nextBytes(cookie);
        return toHexString(cookie);
    }

    @Nonnull
    public static String toHexString(@Nonnull byte[] bytes) {
        StringBuilder buf = new StringBuilder();
        for (byte bb : bytes) {
            int b = bb & 0xFF;
            if (b < 16) buf.append('0');
            buf.append(Integer.toHexString(b));
        }
        return buf.toString();
    }

    static final String COOKIE_NAME = JnlpProtocol3.class.getName() + ".cookie";

    private static final Random RANDOM = new SecureRandom();
}
