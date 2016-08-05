package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Channel;
import hudson.remoting.TestCallable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.net.ssl.SSLContext;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.remoting.nio.NioChannelHub;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.Repeat;
import org.jenkinsci.remoting.protocol.RepeatRule;
import org.jenkinsci.remoting.protocol.cert.RSAKeyPairRule;
import org.jenkinsci.remoting.protocol.cert.SSLContextRule;
import org.jenkinsci.remoting.protocol.cert.X509CertificateRule;
import org.jenkinsci.remoting.protocol.impl.ConnectionRefusalException;
import org.jenkinsci.remoting.util.Charsets;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

@RunWith(Theories.class)
public class JnlpProtocolHandlerTest {

    private static ExecutorService executorService;
    private IOHub selector;
    private NioChannelHub hub;

    private static RSAKeyPairRule clientKey = new RSAKeyPairRule();
    private static RSAKeyPairRule serverKey = new RSAKeyPairRule();
    private static RSAKeyPairRule caRootKey = new RSAKeyPairRule();
    private static X509CertificateRule caRootCert = X509CertificateRule.create("caRoot", caRootKey, caRootKey);
    private static X509CertificateRule clientCert = X509CertificateRule.create("client", clientKey, caRootKey);
    private static X509CertificateRule serverCert = X509CertificateRule.create("server", serverKey, caRootKey);
    private static X509CertificateRule expiredClientCert =
            X509CertificateRule.create("expiredClient", clientKey, caRootKey, -10, -5, TimeUnit.DAYS);
    private static X509CertificateRule notYetValidServerCert =
            X509CertificateRule.create("notYetValidServer", serverKey, caRootKey, +5, +10, TimeUnit.DAYS);
    private static SSLContextRule clientCtx =
            new SSLContextRule("client")
                    .as(clientKey, clientCert, caRootCert)
                    .trusting(caRootCert)
                    .trusting(serverCert);
    private static SSLContextRule serverCtx =
            new SSLContextRule("server")
                    .as(serverKey, serverCert, caRootCert)
                    .trusting(caRootCert)
                    .trusting(clientCert);
    private static SSLContextRule expiredClientCtx =
            new SSLContextRule("expiredClient")
                    .as(clientKey, expiredClientCert, caRootCert)
                    .trusting(caRootCert)
                    .trusting(serverCert);
    private static SSLContextRule notYetValidServerCtx =
            new SSLContextRule("notYetValidServer")
                    .as(serverKey, notYetValidServerCert, caRootCert)
                    .trusting(caRootCert)
                    .trusting(clientCert);
    private static SSLContextRule untrustingClientCtx =
            new SSLContextRule("untrustingClient")
                    .as(clientKey, clientCert)
                    .trusting(caRootCert);
    private static SSLContextRule untrustingServerCtx =
            new SSLContextRule("untrustingServer")
                    .as(serverKey, serverCert)
                    .trusting(caRootCert);
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
    @Rule
    public RepeatRule repeater = new RepeatRule();
    private ServerSocketChannel eastServer;
    private SocketChannel westChannel;
    private SocketChannel eastChannel;
    private Channel eastRemoting;
    private Channel westRemoting;

    @BeforeClass
    public static void setUpClass() throws Exception {
        executorService = Executors.newCachedThreadPool();
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        executorService.shutdownNow();
    }


    @Before
    public void setUp() throws Exception {
        selector = IOHub.create(executorService);
        hub = new NioChannelHub(executorService);
        executorService.submit(hub);
        eastServer = ServerSocketChannel.open();
        eastServer.socket().bind(new InetSocketAddress(0));
        westChannel = SocketChannel.open();
        westChannel.connect(eastServer.getLocalAddress());
        eastChannel = eastServer.accept();
    }

    @After
    public void tearDown() throws Exception {
        IOUtils.closeQuietly(eastRemoting);
        IOUtils.closeQuietly(westRemoting);
        Thread.sleep(10);
        IOUtils.closeQuietly(westChannel);
        IOUtils.closeQuietly(eastChannel);
        IOUtils.closeQuietly(eastServer);
        IOUtils.closeQuietly(hub);
        IOUtils.closeQuietly(selector);
    }

    @Theory
    @Repeat(value = 25, stopAfter = 10, stopAfterUnits = TimeUnit.SECONDS)
    public void happyPath(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        if (useNioHubClient) {
            assumeThat(factory.toString(), not(is("JNLP4-connect")));
        }
        if (lastFactory != factory) {
            System.out.println("Testing factory " + factory);
            lastFactory = factory;
        }
        JnlpProtocolHandler<? extends JnlpConnectionState> eastProto = factory.create(new JnlpClientDatabase() {
            @Override
            public boolean exists(String clientName) {
                return true;
            }

            @Override
            public String getSecretOf(@Nonnull String clientName) {
                return "SuperSecret-" + clientName;
            }
        }, executorService, selector, hub, serverCtx.context(), useNioHubServer);
        JnlpProtocolHandler<? extends JnlpConnectionState> westProto =
                factory.create(null, executorService, selector, useNioHubClient ? hub : null, clientCtx.context()
                        , useNioHubClient);
        HashMap<String, String> westProps = new HashMap<String, String>();
        westProps.put(JnlpConnectionState.CLIENT_NAME_KEY, "happy-path-" + factory);
        westProps.put(JnlpConnectionState.SECRET_KEY, "SuperSecret-happy-path-" + factory);
        Future<Channel> westChan = westProto
                .connect(westChannel.socket(), westProps, new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.approve();
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.BINARY);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        ByteBuffer len = ByteBuffer.wrap(new byte[2]);
        while (len.hasRemaining()) {
            eastChannel.read(len);
        }
        byte[] bytes = new byte[((len.get(0) << 8) & 0xff00) + (len.get(1) & 0xff)];
        ByteBuffer content = ByteBuffer.wrap(bytes);
        while (content.hasRemaining()) {
            eastChannel.read(content);
        }
        assertThat(new String(bytes, Charsets.UTF_8), is("Protocol:" + factory.toString()));
        Future<Channel> eastChan = eastProto
                .handle(eastChannel.socket(), new HashMap<String, String>(), new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.approve();
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.NEGOTIATE);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        eastRemoting = eastChan.get(10, TimeUnit.SECONDS);
        assertThat(eastRemoting, notNullValue());
        eastRemoting.callAsync(new TestCallable());
        westRemoting = westChan.get(10, TimeUnit.SECONDS);
        assertThat(westRemoting, notNullValue());
    }

    private Factory lastFactory;

    @Theory
    public void serverRejects(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        if (lastFactory != factory) {
            System.out.println("Testing factory " + factory);
            lastFactory = factory;
        }
        JnlpProtocolHandler<? extends JnlpConnectionState> eastProto = factory.create(new JnlpClientDatabase() {
            @Override
            public boolean exists(String clientName) {
                return true;
            }

            @Override
            public String getSecretOf(@Nonnull String clientName) {
                return "SuperSecret-" + clientName;
            }
        }, executorService, selector, hub, serverCtx.context(), useNioHubServer);
        JnlpProtocolHandler<? extends JnlpConnectionState> westProto =
                factory.create(null, executorService, selector, useNioHubClient ? hub : null, clientCtx.context(),
                        useNioHubClient);
        HashMap<String, String> westProps = new HashMap<String, String>();
        westProps.put(JnlpConnectionState.CLIENT_NAME_KEY, "happy-path-" + factory);
        westProps.put(JnlpConnectionState.SECRET_KEY, "SuperSecret-happy-path-" + factory);
        Future<Channel> westChan = westProto
                .connect(westChannel.socket(), westProps, new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.approve();
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.BINARY);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        ByteBuffer len = ByteBuffer.wrap(new byte[2]);
        while (len.hasRemaining()) {
            eastChannel.read(len);
        }
        byte[] bytes = new byte[((len.get(0) << 8) & 0xff00) + (len.get(1) & 0xff)];
        ByteBuffer content = ByteBuffer.wrap(bytes);
        while (content.hasRemaining()) {
            eastChannel.read(content);
        }
        assertThat(new String(bytes, Charsets.UTF_8), is("Protocol:" + factory.toString()));
        Future<Channel> eastChan = eastProto
                .handle(eastChannel.socket(), new HashMap<String, String>(), new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.reject(new ConnectionRefusalException("I don't like you"));
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.NEGOTIATE);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        try {
            eastRemoting = eastChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(IOException.class));
        }
        try {
            westRemoting = westChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
    }

    @Theory
    public void serverIgnores(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        if (lastFactory != factory) {
            System.out.println("Testing factory " + factory);
            lastFactory = factory;
        }
        JnlpProtocolHandler<? extends JnlpConnectionState> eastProto = factory.create(new JnlpClientDatabase() {
            @Override
            public boolean exists(String clientName) {
                return true;
            }

            @Override
            public String getSecretOf(@Nonnull String clientName) {
                return "SuperSecret-" + clientName;
            }
        }, executorService, selector, hub, serverCtx.context(), useNioHubServer);
        JnlpProtocolHandler<? extends JnlpConnectionState> westProto =
                factory.create(null, executorService, selector, useNioHubClient ? hub : null, clientCtx.context(),
                        useNioHubClient);
        HashMap<String, String> westProps = new HashMap<String, String>();
        westProps.put(JnlpConnectionState.CLIENT_NAME_KEY, "happy-path-" + factory);
        westProps.put(JnlpConnectionState.SECRET_KEY, "SuperSecret-happy-path-" + factory);
        Future<Channel> westChan = westProto
                .connect(westChannel.socket(), westProps, new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.BINARY);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        ByteBuffer len = ByteBuffer.wrap(new byte[2]);
        while (len.hasRemaining()) {
            eastChannel.read(len);
        }
        byte[] bytes = new byte[((len.get(0) << 8) & 0xff00) + (len.get(1) & 0xff)];
        ByteBuffer content = ByteBuffer.wrap(bytes);
        while (content.hasRemaining()) {
            eastChannel.read(content);
        }
        assertThat(new String(bytes, Charsets.UTF_8), is("Protocol:" + factory.toString()));
        Future<Channel> eastChan = eastProto
                .handle(eastChannel.socket(), new HashMap<String, String>(), new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.reject(new ConnectionRefusalException("I don't like you"));
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.NEGOTIATE);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        try {
            eastRemoting = eastChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(IOException.class));
        }
        try {
            westRemoting = westChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
    }

    @Theory
    public void clientRejects(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        if (lastFactory != factory) {
            System.out.println("Testing factory " + factory);
            lastFactory = factory;
        }
        JnlpProtocolHandler<? extends JnlpConnectionState> eastProto = factory.create(new JnlpClientDatabase() {
            @Override
            public boolean exists(String clientName) {
                return true;
            }

            @Override
            public String getSecretOf(@Nonnull String clientName) {
                return "SuperSecret-" + clientName;
            }
        }, executorService, selector, hub, serverCtx.context(), useNioHubServer);
        JnlpProtocolHandler<? extends JnlpConnectionState> westProto =
                factory.create(null, executorService, selector, useNioHubClient ? hub : null, clientCtx.context(),
                        useNioHubClient);
        HashMap<String, String> westProps = new HashMap<String, String>();
        westProps.put(JnlpConnectionState.CLIENT_NAME_KEY, "happy-path-" + factory);
        westProps.put(JnlpConnectionState.SECRET_KEY, "SuperSecret-happy-path-" + factory);
        Future<Channel> westChan = westProto
                .connect(westChannel.socket(), westProps, new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.reject(new ConnectionRefusalException("I don't like you"));
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.BINARY);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        ByteBuffer len = ByteBuffer.wrap(new byte[2]);
        while (len.hasRemaining()) {
            eastChannel.read(len);
        }
        byte[] bytes = new byte[((len.get(0) << 8) & 0xff00) + (len.get(1) & 0xff)];
        ByteBuffer content = ByteBuffer.wrap(bytes);
        while (content.hasRemaining()) {
            eastChannel.read(content);
        }
        assertThat(new String(bytes, Charsets.UTF_8), is("Protocol:" + factory.toString()));
        Future<Channel> eastChan = eastProto
                .handle(eastChannel.socket(), new HashMap<String, String>(), new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.approve();
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.NEGOTIATE);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        try {
            eastRemoting = eastChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(IOException.class));
        }
        try {
            westRemoting = westChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
    }

    @Theory
    public void clientIgnores(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        if (lastFactory != factory) {
            System.out.println("Testing factory " + factory);
            lastFactory = factory;
        }
        JnlpProtocolHandler<? extends JnlpConnectionState> eastProto = factory.create(new JnlpClientDatabase() {
            @Override
            public boolean exists(String clientName) {
                return true;
            }

            @Override
            public String getSecretOf(@Nonnull String clientName) {
                return "SuperSecret-" + clientName;
            }
        }, executorService, selector, hub, serverCtx.context(), useNioHubServer);
        JnlpProtocolHandler<? extends JnlpConnectionState> westProto =
                factory.create(null, executorService, selector, useNioHubClient ? hub : null, clientCtx.context(),
                        useNioHubClient);
        HashMap<String, String> westProps = new HashMap<String, String>();
        westProps.put(JnlpConnectionState.CLIENT_NAME_KEY, "happy-path-" + factory);
        westProps.put(JnlpConnectionState.SECRET_KEY, "SuperSecret-happy-path-" + factory);
        Future<Channel> westChan = westProto
                .connect(westChannel.socket(), westProps, new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.reject(new ConnectionRefusalException("I don't like you"));
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.BINARY);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        ByteBuffer len = ByteBuffer.wrap(new byte[2]);
        while (len.hasRemaining()) {
            eastChannel.read(len);
        }
        byte[] bytes = new byte[((len.get(0) << 8) & 0xff00) + (len.get(1) & 0xff)];
        ByteBuffer content = ByteBuffer.wrap(bytes);
        while (content.hasRemaining()) {
            eastChannel.read(content);
        }
        assertThat(new String(bytes, Charsets.UTF_8), is("Protocol:" + factory.toString()));
        Future<Channel> eastChan = eastProto
                .handle(eastChannel.socket(), new HashMap<String, String>(), new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.approve();
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.NEGOTIATE);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        try {
            eastRemoting = eastChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(IOException.class));
        }
        try {
            westRemoting = westChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
    }

    @Theory
    public void doesNotExist(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        System.out.println("Testing factory " + factory);
        JnlpProtocolHandler<? extends JnlpConnectionState> eastProto = factory.create(new JnlpClientDatabase() {
            @Override
            public boolean exists(String clientName) {
                return false;
            }

            @Override
            public String getSecretOf(@Nonnull String clientName) {
                return "SuperSecret-" + clientName;
            }
        }, executorService, selector, hub, serverCtx.context(), useNioHubServer);
        JnlpProtocolHandler<? extends JnlpConnectionState> westProto =
                factory.create(null, executorService, selector, useNioHubClient ? hub : null, clientCtx.context(),
                        useNioHubClient);
        HashMap<String, String> westProps = new HashMap<String, String>();
        westProps.put(JnlpConnectionState.CLIENT_NAME_KEY, "happy-path-" + factory);
        westProps.put(JnlpConnectionState.SECRET_KEY, "SuperSecret-happy-path-" + factory);
        Future<Channel> westChan = westProto
                .connect(westChannel.socket(), westProps, new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.approve();
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.BINARY);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        ByteBuffer len = ByteBuffer.wrap(new byte[2]);
        while (len.hasRemaining()) {
            eastChannel.read(len);
        }
        byte[] bytes = new byte[((len.get(0) << 8) & 0xff00) + (len.get(1) & 0xff)];
        ByteBuffer content = ByteBuffer.wrap(bytes);
        while (content.hasRemaining()) {
            eastChannel.read(content);
        }
        assertThat(new String(bytes, Charsets.UTF_8), is("Protocol:" + factory.toString()));
        Future<Channel> eastChan = eastProto
                .handle(eastChannel.socket(), new HashMap<String, String>(), new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.approve();
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.NEGOTIATE);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        try {
            eastRemoting = eastChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
        try {
            westRemoting = westChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
    }

    @Theory
    public void wrongSecret(Factory factory, boolean useNioHubServer, boolean useNioHubClient) throws Exception {
        System.out.println("Testing factory " + factory);
        JnlpProtocolHandler<? extends JnlpConnectionState> eastProto = factory.create(new JnlpClientDatabase() {
            @Override
            public boolean exists(String clientName) {
                return true;
            }

            @Override
            public String getSecretOf(@Nonnull String clientName) {
                return "SuperSecret-" + clientName;
            }
        }, executorService, selector, hub, serverCtx.context(), useNioHubServer);
        JnlpProtocolHandler<? extends JnlpConnectionState> westProto =
                factory.create(null, executorService, selector, useNioHubClient ? hub : null, clientCtx.context(), useNioHubClient);
        HashMap<String, String> westProps = new HashMap<String, String>();
        westProps.put(JnlpConnectionState.CLIENT_NAME_KEY, "happy-path-" + factory);
        westProps.put(JnlpConnectionState.SECRET_KEY, "WrongSecret-happy-path-" + factory);
        Future<Channel> westChan = westProto
                .connect(westChannel.socket(), westProps, new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.approve();
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.BINARY);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        ByteBuffer len = ByteBuffer.wrap(new byte[2]);
        while (len.hasRemaining()) {
            eastChannel.read(len);
        }
        byte[] bytes = new byte[((len.get(0) << 8) & 0xff00) + (len.get(1) & 0xff)];
        ByteBuffer content = ByteBuffer.wrap(bytes);
        while (content.hasRemaining()) {
            eastChannel.read(content);
        }
        assertThat(new String(bytes, Charsets.UTF_8), is("Protocol:" + factory.toString()));
        Future<Channel> eastChan = eastProto
                .handle(eastChannel.socket(), new HashMap<String, String>(), new JnlpConnectionStateListener() {
                    @Override
                    public void afterProperties(@NonNull JnlpConnectionState event) {
                        event.approve();
                    }

                    @Override
                    public void beforeChannel(@NonNull JnlpConnectionState event) {
                        event.getChannelBuilder().withMode(Channel.Mode.NEGOTIATE);
                    }

                    @Override
                    public void afterChannel(@NonNull JnlpConnectionState event) {
                    }
                });
        try {
            eastRemoting = eastChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
        try {
            westRemoting = westChan.get(10, TimeUnit.SECONDS);
            fail();
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(ConnectionRefusalException.class));
        }
    }

    @DataPoints
    public static boolean[] useNioHub() {
        return new boolean[]{true, false};
    }

    @DataPoints
    public static Factory[] protocols() {
        return new Factory[]{
                new Factory() {
                    @Override
                    public JnlpProtocolHandler<? extends JnlpConnectionState> create(JnlpClientDatabase db,
                                                                                     ExecutorService svc,
                                                                                     IOHub selector, NioChannelHub hub,
                                                                                     SSLContext ctx,
                                                                                     boolean preferNio) {
                        return new JnlpProtocol1Handler(db, svc, hub, preferNio);
                    }

                    @Override
                    public String toString() {
                        return "JNLP-connect";
                    }
                },
                new Factory() {
                    @Override
                    public JnlpProtocolHandler<? extends JnlpConnectionState> create(JnlpClientDatabase db,
                                                                                     ExecutorService svc,
                                                                                     IOHub selector, NioChannelHub hub,
                                                                                     SSLContext ctx,
                                                                                     boolean preferNio) {
                        return new JnlpProtocol2Handler(db, svc, hub, preferNio);
                    }

                    @Override
                    public String toString() {
                        return "JNLP2-connect";
                    }
                },
                new Factory() {
                    @Override
                    public JnlpProtocolHandler<? extends JnlpConnectionState> create(JnlpClientDatabase db,
                                                                                     ExecutorService svc,
                                                                                     IOHub selector, NioChannelHub hub,
                                                                                     SSLContext ctx,
                                                                                     boolean preferNio) {
                        return new JnlpProtocol3Handler(db, svc, hub, preferNio);
                    }

                    @Override
                    public String toString() {
                        return "JNLP3-connect";
                    }
                },
                new Factory() {
                    @Override
                    public JnlpProtocolHandler<? extends JnlpConnectionState> create(JnlpClientDatabase db,
                                                                                     ExecutorService svc,
                                                                                     IOHub selector, NioChannelHub hub,
                                                                                     SSLContext ctx,
                                                                                     boolean preferNio) {
                        return new JnlpProtocol4Handler(db, svc, selector, ctx, false, preferNio);

                    }

                    @Override
                    public String toString() {
                        return "JNLP4-connect";
                    }
                },
                new Factory() {
                    @Override
                    public JnlpProtocolHandler<? extends JnlpConnectionState> create(JnlpClientDatabase db,
                                                                                     ExecutorService svc,
                                                                                     IOHub selector, NioChannelHub hub,
                                                                                     SSLContext ctx,
                                                                                     boolean preferNio) {
                        return new JnlpProtocol4PlainHandler(db, svc, selector, preferNio);

                    }

                    @Override
                    public String toString() {
                        return "JNLP4-plaintext";
                    }
                }
        };
    }


    public interface Factory {
        JnlpProtocolHandler<? extends JnlpConnectionState> create(JnlpClientDatabase db, ExecutorService svc,
                                                                  IOHub selector, NioChannelHub hub, SSLContext ctx,
                                                                  boolean preferNio);
    }

}
