package org.jenkinsci.remoting.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.fail;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Channel;
import hudson.remoting.TestCallable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.remoting.nio.NioChannelHub;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.cert.RSAKeyPairRule;
import org.jenkinsci.remoting.protocol.cert.SSLContextRule;
import org.jenkinsci.remoting.protocol.cert.X509CertificateRule;
import org.jenkinsci.remoting.protocol.impl.ConnectionHeadersFilterLayer;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class JnlpProtocolHandlerTest {

    private static final Consumer<JnlpConnectionState> APPROVING_STATE_CONSUMER = JnlpConnectionState::approve;
    private static final Consumer<JnlpConnectionState> REJECTING_STATE_CONSUMER =
            event -> event.reject(new ConnectionRefusalException("I don't like you"));
    private static final Consumer<JnlpConnectionState> IGNORING_STATE_CONSUMER = event -> {};
    private static final String SECRET_KEY = "SecretKey-1234";

    private static ExecutorService executorService;
    private IOHub selector;
    private NioChannelHub hub;

    private static RSAKeyPairRule clientKey = new RSAKeyPairRule();
    private static RSAKeyPairRule serverKey = new RSAKeyPairRule();
    private static RSAKeyPairRule caRootKey = new RSAKeyPairRule();
    private static X509CertificateRule caRootCert = X509CertificateRule.create("caRoot", caRootKey, caRootKey, null);
    private static X509CertificateRule clientCert =
            X509CertificateRule.create("client", clientKey, caRootKey, caRootCert);
    private static X509CertificateRule serverCert =
            X509CertificateRule.create("server", serverKey, caRootKey, caRootCert);
    private static X509CertificateRule expiredClientCert =
            X509CertificateRule.create("expiredClient", clientKey, caRootKey, caRootCert, -10, -5, TimeUnit.DAYS);
    private static X509CertificateRule notYetValidServerCert =
            X509CertificateRule.create("notYetValidServer", serverKey, caRootKey, caRootCert, +5, +10, TimeUnit.DAYS);
    private static SSLContextRule clientCtx = new SSLContextRule("client")
            .as(clientKey, clientCert, caRootCert)
            .trusting(caRootCert)
            .trusting(serverCert);
    private static SSLContextRule serverCtx = new SSLContextRule("server")
            .as(serverKey, serverCert, caRootCert)
            .trusting(caRootCert)
            .trusting(clientCert);
    private static SSLContextRule expiredClientCtx = new SSLContextRule("expiredClient")
            .as(clientKey, expiredClientCert, caRootCert)
            .trusting(caRootCert)
            .trusting(serverCert);
    private static SSLContextRule notYetValidServerCtx = new SSLContextRule("notYetValidServer")
            .as(serverKey, notYetValidServerCert, caRootCert)
            .trusting(caRootCert)
            .trusting(clientCert);
    private static SSLContextRule untrustingClientCtx =
            new SSLContextRule("untrustingClient").as(clientKey, clientCert).trusting(caRootCert);
    private static SSLContextRule untrustingServerCtx =
            new SSLContextRule("untrustingServer").as(serverKey, serverCert).trusting(caRootCert);

    @ClassRule
    public static RuleChain staticCtx = RuleChain.outerRule(caRootKey)
            .around(clientKey)
            .around(serverKey)
            .around(caRootCert)
            .around(clientCert)
            .around(serverCert)
            .around(expiredClientCert)
            .around(notYetValidServerCert)
            .around(clientCtx)
            .around(serverCtx)
            .around(expiredClientCtx)
            .around(notYetValidServerCtx)
            .around(untrustingClientCtx)
            .around(untrustingServerCtx);

    private ServerSocketChannel baseServerSocket;
    private SocketChannel clientSocketChannel;
    private SocketChannel serverSocketChannel;
    private Channel serverRemotingChannel;
    private Channel clientRemotingChannel;

    @BeforeClass
    public static void setUpClass() {
        Logger.getLogger(ConnectionHeadersFilterLayer.class.getName()).setLevel(Level.WARNING);
        executorService = Executors.newCachedThreadPool();
    }

    @AfterClass
    public static void tearDownClass() {
        executorService.shutdownNow();
    }

    @Before
    public void setUp() throws Exception {
        selector = IOHub.create(executorService);
        hub = new NioChannelHub(executorService);
        executorService.submit(hub);
        baseServerSocket = ServerSocketChannel.open();
        baseServerSocket.socket().bind(new InetSocketAddress(0));
        clientSocketChannel = SocketChannel.open();
        clientSocketChannel.connect(baseServerSocket.getLocalAddress());
        serverSocketChannel = baseServerSocket.accept();
    }

    @After
    public void tearDown() {
        IOUtils.closeQuietly(serverRemotingChannel);
        IOUtils.closeQuietly(clientRemotingChannel);
        IOUtils.closeQuietly(clientSocketChannel);
        IOUtils.closeQuietly(serverSocketChannel);
        IOUtils.closeQuietly(baseServerSocket);
        IOUtils.closeQuietly(hub);
        IOUtils.closeQuietly(selector);
    }

    @Theory
    public void happyPath(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Throwable {
        JnlpProtocolHandler<? extends JnlpConnectionState> serverProtocolHandler =
                createServerProtocolHandler(factory, useNioHubServer, SECRET_KEY, true);
        JnlpProtocolHandler<? extends JnlpConnectionState> clientProtocolHandler =
                createClientProtocolHandler(factory, useNioHubClient);
        HashMap<String, String> clientProps = createClientProperties(factory, SECRET_KEY);
        Future<Channel> clientChannelFuture = createChannelConnector(
                clientSocketChannel, clientProtocolHandler, clientProps, APPROVING_STATE_CONSUMER);
        readAndCheckProtocol(factory);
        Future<Channel> serverChannelFuture = createChannelHandler(
                serverSocketChannel, serverProtocolHandler, new HashMap<>(), APPROVING_STATE_CONSUMER);
        serverRemotingChannel = serverChannelFuture.get(10, TimeUnit.SECONDS);
        assertThat(serverRemotingChannel, notNullValue());
        serverRemotingChannel.call(new TestCallable());
        clientRemotingChannel = clientChannelFuture.get(10, TimeUnit.SECONDS);
        assertThat(clientRemotingChannel, notNullValue());
    }

    @Theory
    public void serverRejects(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        JnlpProtocolHandler<? extends JnlpConnectionState> serverProtocolHandler =
                createServerProtocolHandler(factory, useNioHubServer, SECRET_KEY, true);
        JnlpProtocolHandler<? extends JnlpConnectionState> clientProtocolHandler =
                createClientProtocolHandler(factory, useNioHubClient);
        HashMap<String, String> clientProps = createClientProperties(factory, SECRET_KEY);
        Future<Channel> clientChannelFuture = createChannelConnector(
                clientSocketChannel, clientProtocolHandler, clientProps, APPROVING_STATE_CONSUMER);
        readAndCheckProtocol(factory);
        Future<Channel> serverChannelFuture = createChannelHandler(
                serverSocketChannel, serverProtocolHandler, new HashMap<>(), REJECTING_STATE_CONSUMER);
        assertChannelFails(clientChannelFuture, serverChannelFuture, ConnectionRefusalException.class);
    }

    @Theory
    public void serverIgnores(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        JnlpProtocolHandler<? extends JnlpConnectionState> serverProtocolHandler =
                createServerProtocolHandler(factory, useNioHubServer, SECRET_KEY, true);
        JnlpProtocolHandler<? extends JnlpConnectionState> clientProtocolHandler =
                createClientProtocolHandler(factory, useNioHubClient);
        HashMap<String, String> clientProps = createClientProperties(factory, SECRET_KEY);
        Future<Channel> clientChannelFuture = createChannelConnector(
                clientSocketChannel, clientProtocolHandler, clientProps, IGNORING_STATE_CONSUMER);
        readAndCheckProtocol(factory);
        Future<Channel> serverChannelFuture = createChannelHandler(
                serverSocketChannel, serverProtocolHandler, new HashMap<>(), APPROVING_STATE_CONSUMER);
        assertChannelFails(clientChannelFuture, serverChannelFuture, IOException.class);
    }

    @Theory
    public void clientRejects(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        JnlpProtocolHandler<? extends JnlpConnectionState> serverProtocolHandler =
                createServerProtocolHandler(factory, useNioHubServer, SECRET_KEY, true);
        JnlpProtocolHandler<? extends JnlpConnectionState> clientProtocolHandler =
                createClientProtocolHandler(factory, useNioHubClient);
        HashMap<String, String> clientProps = createClientProperties(factory, SECRET_KEY);
        Future<Channel> clientChannelFuture = createChannelConnector(
                clientSocketChannel, clientProtocolHandler, clientProps, APPROVING_STATE_CONSUMER);
        readAndCheckProtocol(factory);
        Future<Channel> serverChannelFuture = createChannelHandler(
                serverSocketChannel, serverProtocolHandler, new HashMap<>(), REJECTING_STATE_CONSUMER);
        assertChannelFails(clientChannelFuture, serverChannelFuture, IOException.class);
    }

    @Theory
    public void clientIgnores(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        JnlpProtocolHandler<? extends JnlpConnectionState> serverProtocolHandler =
                createServerProtocolHandler(factory, useNioHubServer, SECRET_KEY, true);
        JnlpProtocolHandler<? extends JnlpConnectionState> clientProtocolHandler =
                createClientProtocolHandler(factory, useNioHubClient);
        HashMap<String, String> clientProps = createClientProperties(factory, SECRET_KEY);
        Future<Channel> clientChannelFuture = createChannelConnector(
                clientSocketChannel, clientProtocolHandler, clientProps, APPROVING_STATE_CONSUMER);
        readAndCheckProtocol(factory);
        Future<Channel> serverChannelFuture = createChannelHandler(
                serverSocketChannel, serverProtocolHandler, new HashMap<>(), IGNORING_STATE_CONSUMER);
        assertChannelFails(clientChannelFuture, serverChannelFuture, ConnectionRefusalException.class);
    }

    @Theory
    public void doesNotExist(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        JnlpProtocolHandler<? extends JnlpConnectionState> serverProtocolHandler =
                createServerProtocolHandler(factory, useNioHubServer, SECRET_KEY, false);
        JnlpProtocolHandler<? extends JnlpConnectionState> clientProtocolHandler =
                createClientProtocolHandler(factory, useNioHubClient);
        HashMap<String, String> clientProps = createClientProperties(factory, SECRET_KEY);
        Future<Channel> clientChannelFuture = createChannelConnector(
                clientSocketChannel, clientProtocolHandler, clientProps, APPROVING_STATE_CONSUMER);
        readAndCheckProtocol(factory);
        Future<Channel> serverChannelFuture = createChannelHandler(
                serverSocketChannel, serverProtocolHandler, new HashMap<>(), APPROVING_STATE_CONSUMER);
        assertChannelFails(clientChannelFuture, serverChannelFuture, ConnectionRefusalException.class);
    }

    @Theory
    public void wrongSecret(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        Logger.getLogger(JnlpProtocol4Handler.class.getName()).setLevel(Level.SEVERE);
        JnlpProtocolHandler<? extends JnlpConnectionState> serverProtocolHandler =
                createServerProtocolHandler(factory, useNioHubServer, SECRET_KEY, true);
        JnlpProtocolHandler<? extends JnlpConnectionState> clientProtocolHandler =
                createClientProtocolHandler(factory, useNioHubClient);
        HashMap<String, String> clientProps = createClientProperties(factory, "WrongSecret");
        Future<Channel> clientChannelFuture = createChannelConnector(
                clientSocketChannel, clientProtocolHandler, clientProps, APPROVING_STATE_CONSUMER);
        readAndCheckProtocol(factory);
        Future<Channel> serverChannelFuture = createChannelHandler(
                serverSocketChannel, serverProtocolHandler, new HashMap<>(), APPROVING_STATE_CONSUMER);
        assertChannelFails(clientChannelFuture, serverChannelFuture, ConnectionRefusalException.class);
    }

    private Future<Channel> createChannelConnector(
            SocketChannel channel,
            JnlpProtocolHandler<? extends JnlpConnectionState> protocolHandler,
            HashMap<String, String> properties,
            Consumer<JnlpConnectionState> afterPropertiesConsumer)
            throws IOException {
        return protocolHandler.connect(
                channel.socket(), properties, new StateListener(afterPropertiesConsumer, Channel.Mode.BINARY));
    }

    private Future<Channel> createChannelHandler(
            SocketChannel channel,
            JnlpProtocolHandler<? extends JnlpConnectionState> protocolHandler,
            HashMap<String, String> properties,
            Consumer<JnlpConnectionState> afterPropertiesConsumer)
            throws IOException {
        return protocolHandler.handle(
                channel.socket(), properties, new StateListener(afterPropertiesConsumer, Channel.Mode.NEGOTIATE));
    }

    private HashMap<String, String> createClientProperties(Factory factory, String secretKey) {
        HashMap<String, String> clientProps = new HashMap<>();
        clientProps.put(JnlpConnectionState.CLIENT_NAME_KEY, "client-" + factory);
        clientProps.put(JnlpConnectionState.SECRET_KEY, secretKey);
        return clientProps;
    }

    private JnlpProtocolHandler<? extends JnlpConnectionState> createClientProtocolHandler(
            Factory factory, boolean useNioHubClient) {
        return factory.create(
                null, executorService, selector, useNioHubClient ? hub : null, clientCtx.context(), useNioHubClient);
    }

    private JnlpProtocolHandler<? extends JnlpConnectionState> createServerProtocolHandler(
            Factory factory, boolean useNioHubServer, String secretKey, boolean exists) {
        return factory.create(
                new JnlpClientDatabase() {
                    @Override
                    public boolean exists(String clientName) {
                        return exists;
                    }

                    @Override
                    public String getSecretOf(@NonNull String clientName) {
                        return secretKey;
                    }
                },
                executorService,
                selector,
                hub,
                serverCtx.context(),
                useNioHubServer);
    }

    private void readAndCheckProtocol(Factory factory) throws IOException {
        ByteBuffer len = ByteBuffer.wrap(new byte[2]);
        while (len.hasRemaining()) {
            serverSocketChannel.read(len);
        }
        byte[] bytes = new byte[((len.get(0) << 8) & 0xff00) + (len.get(1) & 0xff)];
        ByteBuffer content = ByteBuffer.wrap(bytes);
        while (content.hasRemaining()) {
            serverSocketChannel.read(content);
        }
        assertThat(new String(bytes, StandardCharsets.UTF_8), is("Protocol:" + factory.toString()));
    }

    private void assertChannelFails(
            Future<Channel> clientChannelFuture,
            Future<Channel> serverChannelFuture,
            Class<? extends Exception> serverExceptionType)
            throws InterruptedException, TimeoutException {
        try {
            serverRemotingChannel = serverChannelFuture.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(serverExceptionType));
        }
        try {
            clientRemotingChannel = clientChannelFuture.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(IOException.class));
        }
    }

    @DataPoints
    public static boolean[] useNioHub() {
        return new boolean[] {true, false};
    }

    @DataPoints
    public static Factory[] protocols() {
        return new Factory[] {
            new Factory() {
                @Override
                public JnlpProtocolHandler<? extends JnlpConnectionState> create(
                        JnlpClientDatabase db,
                        ExecutorService svc,
                        IOHub selector,
                        NioChannelHub hub,
                        SSLContext ctx,
                        boolean preferNio) {
                    return new JnlpProtocol4Handler(db, svc, selector, ctx, false, preferNio);
                }

                @Override
                public String toString() {
                    return "JNLP4-connect";
                }
            }
        };
    }

    private static class StateListener extends JnlpConnectionStateListener {
        private Channel.Mode mode;
        private Consumer<JnlpConnectionState> afterPropertiesConsumer;

        StateListener(Consumer<JnlpConnectionState> afterPropertiesConsumer, Channel.Mode mode) {
            this.mode = mode;
            this.afterPropertiesConsumer = afterPropertiesConsumer;
        }

        @Override
        public void afterProperties(@NonNull JnlpConnectionState event) {
            afterPropertiesConsumer.accept(event);
        }

        @Override
        public void beforeChannel(@NonNull JnlpConnectionState event) {
            event.getChannelBuilder().withMode(mode);
        }

        @Override
        public void afterChannel(@NonNull JnlpConnectionState event) {}
    }

    public interface Factory {
        JnlpProtocolHandler<? extends JnlpConnectionState> create(
                JnlpClientDatabase db,
                ExecutorService svc,
                IOHub selector,
                NioChannelHub hub,
                SSLContext ctx,
                boolean preferNio);
    }
}
