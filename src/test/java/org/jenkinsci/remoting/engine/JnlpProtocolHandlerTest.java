package org.jenkinsci.remoting.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.remoting.nio.NioChannelHub;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.cert.RSAKeyPairExtension;
import org.jenkinsci.remoting.protocol.cert.SSLContextExtension;
import org.jenkinsci.remoting.protocol.cert.X509CertificateExtension;
import org.jenkinsci.remoting.protocol.impl.ConnectionHeadersFilterLayer;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@ParameterizedClass
@MethodSource("parameters")
class JnlpProtocolHandlerTest {

    private static final Consumer<JnlpConnectionState> APPROVING_STATE_CONSUMER = JnlpConnectionState::approve;
    private static final Consumer<JnlpConnectionState> REJECTING_STATE_CONSUMER =
            event -> event.reject(new ConnectionRefusalException("I don't like you"));
    private static final Consumer<JnlpConnectionState> IGNORING_STATE_CONSUMER = event -> {};
    private static final String SECRET_KEY = "SecretKey-1234";

    private static ExecutorService executorService;
    private IOHub selector;
    private NioChannelHub hub;

    @Order(0)
    @RegisterExtension
    private static final RSAKeyPairExtension CA_ROOT_KEY = new RSAKeyPairExtension();

    @Order(1)
    @RegisterExtension
    private static final RSAKeyPairExtension CLIENT_KEY = new RSAKeyPairExtension();

    @Order(2)
    @RegisterExtension
    private static final RSAKeyPairExtension SERVER_KEY = new RSAKeyPairExtension();

    @Order(3)
    @RegisterExtension
    private static final X509CertificateExtension CA_ROOT_CERT =
            X509CertificateExtension.create("caRoot", CA_ROOT_KEY, CA_ROOT_KEY, null);

    @Order(4)
    @RegisterExtension
    private static final X509CertificateExtension CLIENT_CERT =
            X509CertificateExtension.create("client", CLIENT_KEY, CA_ROOT_KEY, CA_ROOT_CERT);

    @Order(5)
    @RegisterExtension
    private static final X509CertificateExtension SERVER_CERT =
            X509CertificateExtension.create("server", SERVER_KEY, CA_ROOT_KEY, CA_ROOT_CERT);

    @Order(6)
    @RegisterExtension
    private static final X509CertificateExtension EXPIRED_CLIENT_CERT = X509CertificateExtension.create(
            "expiredClient", CLIENT_KEY, CA_ROOT_KEY, CA_ROOT_CERT, -10, -5, TimeUnit.DAYS);

    @Order(7)
    @RegisterExtension
    private static final X509CertificateExtension NOT_YET_VALID_SERVER_CERT = X509CertificateExtension.create(
            "notYetValidServer", SERVER_KEY, CA_ROOT_KEY, CA_ROOT_CERT, +5, +10, TimeUnit.DAYS);

    @Order(8)
    @RegisterExtension
    private static final SSLContextExtension CLIENT_CTX = new SSLContextExtension("client")
            .as(CLIENT_KEY, CLIENT_CERT, CA_ROOT_CERT)
            .trusting(CA_ROOT_CERT)
            .trusting(SERVER_CERT);

    @Order(9)
    @RegisterExtension
    private static final SSLContextExtension SERVER_CTX = new SSLContextExtension("server")
            .as(SERVER_KEY, SERVER_CERT, CA_ROOT_CERT)
            .trusting(CA_ROOT_CERT)
            .trusting(CLIENT_CERT);

    @Order(10)
    @RegisterExtension
    private static final SSLContextExtension EXPIRED_CLIENT_CTX = new SSLContextExtension("expiredClient")
            .as(CLIENT_KEY, EXPIRED_CLIENT_CERT, CA_ROOT_CERT)
            .trusting(CA_ROOT_CERT)
            .trusting(SERVER_CERT);

    @Order(11)
    @RegisterExtension
    private static final SSLContextExtension NOT_YET_VALID_SERVER_CTX = new SSLContextExtension("notYetValidServer")
            .as(SERVER_KEY, NOT_YET_VALID_SERVER_CERT, CA_ROOT_CERT)
            .trusting(CA_ROOT_CERT)
            .trusting(CLIENT_CERT);

    @Order(12)
    @RegisterExtension
    private static final SSLContextExtension UNTRUSTING_CLIENT_CTX = new SSLContextExtension("untrustingClient")
            .as(CLIENT_KEY, CLIENT_CERT)
            .trusting(CA_ROOT_CERT);

    @Order(13)
    @RegisterExtension
    private static final SSLContextExtension UNTRUSTING_SERVER_CTX = new SSLContextExtension("untrustingServer")
            .as(SERVER_KEY, SERVER_CERT)
            .trusting(CA_ROOT_CERT);

    @Parameter(0)
    private Factory factory;

    @Parameter(1)
    private boolean useNioHubServer;

    @Parameter(2)
    private boolean useNioHubClient;

    private ServerSocketChannel baseServerSocket;
    private SocketChannel clientSocketChannel;
    private SocketChannel serverSocketChannel;
    private Channel serverRemotingChannel;
    private Channel clientRemotingChannel;

    @BeforeAll
    static void beforeAll() {
        Logger.getLogger(ConnectionHeadersFilterLayer.class.getName()).setLevel(Level.WARNING);
        executorService = Executors.newCachedThreadPool();
    }

    @AfterAll
    static void afterAll() {
        executorService.shutdownNow();
    }

    @BeforeEach
    void beforeEach() throws Exception {
        selector = IOHub.create(executorService);
        hub = new NioChannelHub(executorService);
        executorService.submit(hub);
        baseServerSocket = ServerSocketChannel.open();
        baseServerSocket.socket().bind(new InetSocketAddress(0));
        clientSocketChannel = SocketChannel.open();
        clientSocketChannel.connect(baseServerSocket.getLocalAddress());
        serverSocketChannel = baseServerSocket.accept();
    }

    @AfterEach
    void afterEach() {
        IOUtils.closeQuietly(serverRemotingChannel);
        IOUtils.closeQuietly(clientRemotingChannel);
        IOUtils.closeQuietly(clientSocketChannel);
        IOUtils.closeQuietly(serverSocketChannel);
        IOUtils.closeQuietly(baseServerSocket);
        IOUtils.closeQuietly(hub);
        IOUtils.closeQuietly(selector);
    }

    @Test
    void happyPath() throws Throwable {
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

    @Test
    void serverRejects() throws Exception {
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

    @Test
    void serverIgnores() throws Exception {
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

    @Test
    void clientRejects() throws Exception {
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

    @Test
    void clientIgnores() throws Exception {
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

    @Test
    void doesNotExist() throws Exception {
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

    @Test
    void wrongSecret() throws Exception {
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
                null, executorService, selector, useNioHubClient ? hub : null, CLIENT_CTX.context(), useNioHubClient);
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
                SERVER_CTX.context(),
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

    static Stream<Arguments> parameters() {
        Factory factory = new Factory() {
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
        };

        return Stream.of(
                Arguments.of(factory, true, true),
                Arguments.of(factory, true, false),
                Arguments.of(factory, false, true),
                Arguments.of(factory, false, false));
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
