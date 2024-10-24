/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.SocketChannelStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryManagerMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.jenkinsci.remoting.RoleChecker;
import org.jenkinsci.remoting.nio.NioChannelHub;
import org.jenkinsci.remoting.protocol.IOHub;
import org.jenkinsci.remoting.protocol.IOHubReadyListener;
import org.jenkinsci.remoting.protocol.IOHubRegistrationCallback;
import org.jenkinsci.remoting.protocol.cert.BlindTrustX509ExtendedTrustManager;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/**
 * A stress-testing client
 */
public class HandlerLoopbackLoadStress {

    private static final BouncyCastleProvider BOUNCY_CASTLE_PROVIDER = new BouncyCastleProvider();

    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private final Timer[] timer = createTimers();
    private final JnlpConnectionStateListener serverListener =
            new MyJnlpConnectionStateListener(Channel.Mode.NEGOTIATE);
    private final JnlpConnectionStateListener clientListener = new MyJnlpConnectionStateListener(Channel.Mode.BINARY);

    private final IOHub mainHub;
    private final IOHub acceptorHub;

    private final ServerSocketChannel serverSocketChannel;

    private final Acceptor acceptor;

    private final JnlpProtocolHandler<? extends JnlpConnectionState> handler;

    private final CompletableFuture<SocketAddress> addr = new CompletableFuture<>();
    private final Random entropy = new Random();

    private final RuntimeMXBean runtimeMXBean;
    private final List<GarbageCollectorMXBean> garbageCollectorMXBeans;
    private final OperatingSystemMXBean operatingSystemMXBean;
    private final @CheckForNull Method _getProcessCpuTime;
    private final Config config;
    private final Stats stats;

    public HandlerLoopbackLoadStress(Config config)
            throws IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException,
                    UnrecoverableKeyException, KeyManagementException, OperatorCreationException {
        this.config = config;
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048); // maximum supported by JVM with export restrictions
        KeyPair keyPair = gen.generateKeyPair();

        Date now = new Date();
        Date firstDate = new Date(now.getTime() + TimeUnit.DAYS.toMillis(10));
        Date lastDate = new Date(now.getTime() + TimeUnit.DAYS.toMillis(-10));

        SubjectPublicKeyInfo subjectPublicKeyInfo =
                SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded());

        X500NameBuilder nameBuilder = new X500NameBuilder(BCStyle.INSTANCE);
        X500Name subject = nameBuilder
                .addRDN(BCStyle.CN, getClass().getSimpleName())
                .addRDN(BCStyle.C, "US")
                .build();

        X509v3CertificateBuilder certGen = new X509v3CertificateBuilder(
                subject, BigInteger.ONE, firstDate, lastDate, subject, subjectPublicKeyInfo);

        JcaX509ExtensionUtils instance = new JcaX509ExtensionUtils();

        certGen.addExtension(
                Extension.subjectKeyIdentifier, false, instance.createSubjectKeyIdentifier(subjectPublicKeyInfo));

        ContentSigner signer = new JcaContentSignerBuilder("SHA1withRSA")
                .setProvider(BOUNCY_CASTLE_PROVIDER)
                .build(keyPair.getPrivate());

        X509Certificate certificate = new JcaX509CertificateConverter()
                .setProvider(BOUNCY_CASTLE_PROVIDER)
                .getCertificate(certGen.build(signer));

        char[] password = "password".toCharArray();

        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, password);
        store.setKeyEntry("alias", keyPair.getPrivate(), password, new Certificate[] {certificate});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, password);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), new TrustManager[] {new BlindTrustX509ExtendedTrustManager()}, null);

        mainHub = IOHub.create(executorService);
        // on windows there is a bug whereby you cannot mix ServerSockets and Sockets on the same selector
        acceptorHub = File.pathSeparatorChar == 59 ? IOHub.create(executorService) : mainHub;
        NioChannelHub legacyHub = new NioChannelHub(executorService);
        executorService.submit(legacyHub);
        serverSocketChannel = ServerSocketChannel.open();

        JnlpProtocolHandler<? extends JnlpConnectionState> handler = null;
        for (JnlpProtocolHandler<? extends JnlpConnectionState> h : new JnlpProtocolHandlerFactory(executorService)
                .withNioChannelHub(legacyHub)
                .withIOHub(mainHub)
                .withSSLContext(context)
                .withPreferNonBlockingIO(!config.bio)
                .withClientDatabase(new JnlpClientDatabase() {
                    @Override
                    public boolean exists(String clientName) {
                        return true;
                    }

                    @Override
                    public String getSecretOf(@NonNull String clientName) {
                        return secretFor(clientName);
                    }
                })
                .withSSLClientAuthRequired(false)
                .handlers()) {
            if (config.name.equals(h.getName())) {
                handler = h;
                break;
            }
        }
        if (handler == null) {
            throw new RuntimeException("Unknown handler: " + config.name);
        }
        this.handler = handler;

        acceptor = new Acceptor(serverSocketChannel);
        runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        if (operatingSystemMXBean instanceof com.sun.management.OperatingSystemMXBean) {
            // YAGNI, we will do it without reflection then
            _getProcessCpuTime = null;
        } else {
            _getProcessCpuTime = _getProcessCpuTime(operatingSystemMXBean);
        }
        garbageCollectorMXBeans = new ArrayList<>(ManagementFactory.getGarbageCollectorMXBeans());
        garbageCollectorMXBeans.sort(Comparator.comparing(MemoryManagerMXBean::getName));
        stats = new Stats();
    }

    private static String secretFor(@NonNull String clientName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.reset();
            byte[] bytes = digest.digest(
                    (HandlerLoopbackLoadStress.class.getName() + clientName).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(Math.max(0, bytes.length * 3 - 1));
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) {
                    result.append(':');
                }
                result.append(Character.forDigit((bytes[i] >> 4) & 0x0f, 16));
                result.append(Character.forDigit(bytes[i] & 0x0f, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JLS mandates MD5 support");
        }
    }

    private static InetSocketAddress toSocketAddress(String hostPort) {
        InetSocketAddress socketAddress;
        if (hostPort == null || hostPort.trim().isEmpty()) {
            socketAddress = new InetSocketAddress(0);
        } else {
            int index = hostPort.indexOf(':');
            if (index == -1) {
                socketAddress = new InetSocketAddress(hostPort, 0);
            } else if (index > 0) {
                int port = Integer.parseInt(hostPort.substring(index + 1));
                socketAddress = new InetSocketAddress(hostPort.substring(0, index), port);
            } else {
                int port = Integer.parseInt(hostPort.substring(index + 1));
                socketAddress = new InetSocketAddress(port);
            }
        }
        return socketAddress;
    }

    public static void main(String[] args) throws Exception {
        final Config config = new Config();
        CmdLineParser p = new CmdLineParser(config);
        try {
            p.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            p.printUsage(System.err);
            System.exit(0);
        }
        if (config.help) {
            p.printUsage(System.err);
            System.exit(0);
        }
        System.out.printf(
                "Starting stress test of %s with %d clients making calls (payload %d bytes) every %dms "
                        + "(%.1f/sec) to give a total expected rate of %.1f/sec%n",
                config.name,
                config.numClients,
                config.payload,
                config.clientIntervalMs,
                1000.0 / config.clientIntervalMs,
                1000.0 / config.clientIntervalMs * config.numClients);
        System.out.println(!config.bio ? "Preferring NIO" : "Prefering BIO");
        final HandlerLoopbackLoadStress stress = new HandlerLoopbackLoadStress(config);
        stress.mainHub.execute(stress.stats);
        final SocketAddress serverAddress;
        if (config.client == null) {
            serverAddress = stress.startServer(config.listen);
            TimeUnit.SECONDS.sleep(1);
        } else {
            serverAddress = toSocketAddress(config.client);
        }
        try {
            if (!config.server) {
                final CountDownLatch started = new CountDownLatch(config.numClients);
                List<Future<Void>> clients = new ArrayList<>(config.numClients);
                for (int i = 0; i < config.numClients; i++) {
                    if (config.connectDelay > 0) {
                        Thread.sleep(config.connectDelay);
                    }
                    if (i % 10 == 0) {
                        System.out.println("Starting client " + i);
                    }
                    final int clientNumber = i;
                    clients.add(stress.executorService.submit(() -> {
                        try {
                            stress.startClient(clientNumber, serverAddress, config.clientIntervalMs, config.payload);
                        } finally {
                            started.countDown();
                        }
                        return null;
                    }));
                }
                for (Future<Void> future : clients) {
                    future.get(60, TimeUnit.SECONDS);
                }
                started.await(60, TimeUnit.SECONDS);
                System.out.println("All clients started");
                stress.stats.clientsStarted();
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Resolves the {@code getProcessCpuTime} method.
     * This method is guaranteed to be available in {@link com.sun.management.OperatingSystemMXBean},
     * but not in the universal package.
     * @param operatingSystemMXBean Bean
     * @return Method or {@code null} if it does not exist
     */
    @CheckForNull
    private static Method _getProcessCpuTime(OperatingSystemMXBean operatingSystemMXBean) {
        Method getProcessCpuTime;
        try {
            getProcessCpuTime = operatingSystemMXBean.getClass().getMethod("getProcessCpuTime");
            getProcessCpuTime.setAccessible(true);
        } catch (ClassCastException | NoSuchMethodException e) {
            getProcessCpuTime = null;
        }
        return getProcessCpuTime;
    }

    private Timer[] createTimers() {
        Timer[] result = new Timer[Runtime.getRuntime().availableProcessors()];
        for (int i = 0; i < result.length; i++) {
            result[i] = new Timer(true);
        }
        return result;
    }

    private SocketAddress startServer(String listen)
            throws IOException, ExecutionException, InterruptedException, TimeoutException {
        serverSocketChannel.bind(toSocketAddress(listen));
        serverSocketChannel.configureBlocking(false);
        acceptorHub.register(serverSocketChannel, acceptor, true, false, false, false, acceptor);
        acceptor.registered.get(10, TimeUnit.SECONDS);
        return addr.get();
    }

    @CheckForNull
    private Long getProcessCpuTime() {
        Object r = null;
        if (operatingSystemMXBean instanceof com.sun.management.OperatingSystemMXBean) {
            r = ((com.sun.management.OperatingSystemMXBean) operatingSystemMXBean).getProcessCpuTime();
        } else if (_getProcessCpuTime != null) {
            // Then we try reflection, if the method was located
            try {
                r = _getProcessCpuTime.invoke(operatingSystemMXBean);
            } catch (IllegalAccessException | InvocationTargetException e) {
                // Do nothing on failure
            }
        }
        if (r instanceof Number) {
            long value = ((Number) r).longValue();
            return (value >= 0) ? value : null;
        }
        return null;
    }

    private void startClient(int n, SocketAddress serverAddress, final int clientIntervalMs, final int payloadSize)
            throws IOException, ExecutionException, InterruptedException, TimeoutException {
        SocketChannel toServer = SocketChannel.open();
        toServer.socket().setKeepAlive(true);
        toServer.socket().setTcpNoDelay(true);
        toServer.configureBlocking(true);
        toServer.connect(serverAddress);
        HashMap<String, String> headers = new HashMap<>();
        String clientName = runtimeMXBean.getName() + "-client-" + n;
        headers.put(JnlpConnectionState.CLIENT_NAME_KEY, clientName);
        headers.put(JnlpConnectionState.SECRET_KEY, secretFor(clientName));
        final Channel clientChannel =
                handler.connect(toServer.socket(), headers, clientListener).get(15, TimeUnit.SECONDS);
        timer[n % timer.length].scheduleAtFixedRate(
                new TimerTask() {
                    long start = System.currentTimeMillis();
                    int index = 0;
                    int times = 0;
                    private NoOpCallable callable = new NoOpCallable(payloadSize == -1 ? null : new byte[payloadSize]);

                    @Override
                    public void run() {
                        try {
                            long start = System.currentTimeMillis();
                            clientChannel.call(callable);
                            if (config.client != null) {
                                NoOpCallable.noops.incrementAndGet();
                            }
                            times++;
                            if (times % 1000 == 0) {
                                System.out.printf(
                                        "  %s has run %d No-op callables. Rate %.1f/s expect %.1f/s%n",
                                        clientChannel.getName(),
                                        times,
                                        times * 1000.0 / (System.currentTimeMillis() - this.start),
                                        1000.0 / clientIntervalMs);
                            }
                            long duration = System.currentTimeMillis() - start;
                            if (duration > 250L) {
                                System.err.printf(
                                        "  %s took %dms to complete a callable%n", clientChannel.getName(), duration);
                            }
                            if (callable.payload != null && callable.payload.length > 0) {
                                // mutate the payload to prevent compression
                                int count = callable.payload.length;
                                if (count > 100) {
                                    count = 100;
                                }
                                for (int j = 0; j < count; j++) {
                                    callable.payload[index] = (byte) (callable.payload[index] * 31 + times);
                                    index = Math.abs(index + 1) % callable.payload.length;
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace(System.err);
                            IOUtils.closeQuietly(clientChannel);
                            cancel();
                            System.exit(2);
                        }
                    }
                },
                entropy.nextInt(clientIntervalMs),
                clientIntervalMs);
    }

    public static class Config {
        @Option(name = "--protocol", metaVar = "PROTOCOL", usage = "The protocol to run the load test with")
        public String name = "JNLP4-connect";

        @Option(name = "--clients", metaVar = "CLIENTS", usage = "The number of clients to simulate")
        public int numClients = 100;

        @Option(
                name = "--interval",
                metaVar = "MILLISECONDS",
                usage = "The number of milliseconds each client waits before sending a command")
        public int clientIntervalMs = 100;

        @Option(name = "--size", metaVar = "BYTES", usage = "The number of bytes to pad the command with")
        public int payload = -1;

        @Option(
                name = "--warmup",
                metaVar = "SECONDS",
                usage = "The number of seconds after all connections are established to warm up before resetting stats")
        public int warmup = -1;

        @Option(
                name = "--collect",
                metaVar = "SECONDS",
                usage = "The number of seconds after all connections are established to collect stats for before "
                        + "stopping")
        public int collect = -1;

        @Option(name = "--stats", metaVar = "FILE", usage = "Filename to record stats to")
        public String file;

        @Option(name = "--bio")
        public boolean bio;

        @Option(name = "--listen", metaVar = "HOST:PORT", usage = "Specify the hostname and port to listen on")
        public String listen;

        @Option(name = "--server", usage = "Specify to run as a server only")
        public boolean server;

        @Option(
                name = "--client",
                metaVar = "HOST:PORT",
                usage = "Specify to run as a client only and connect to a server on the specified HOST:PORT")
        public String client;

        @Option(
                name = "--connect",
                metaVar = "MILLIS",
                usage = "The number of milliseconds to wait between client starts")
        public int connectDelay = -1;

        @Option(
                name = "--help",
                aliases = {"-h", "-?"})
        public boolean help;
    }

    private static class MyJnlpConnectionStateListener extends JnlpConnectionStateListener {
        private final Channel.Mode mode;

        public MyJnlpConnectionStateListener(Channel.Mode mode) {
            this.mode = mode;
        }

        @Override
        public void afterProperties(@NonNull JnlpConnectionState event) {
            event.approve();
        }

        @Override
        public void beforeChannel(@NonNull JnlpConnectionState event) {
            event.getChannelBuilder().withMode(mode);
        }

        @Override
        public void afterChannel(@NonNull JnlpConnectionState event) {
            String clientName = event.getProperty(JnlpConnectionState.CLIENT_NAME_KEY);
            if (clientName != null) {
                System.out.println("Accepted connection from client " + clientName + " on "
                        + event.getRemoteEndpointDescription());
            }
        }
    }

    private static class NoOpCallable implements Callable<Void, IOException> {

        private static final AtomicLong noops = new AtomicLong();

        private final byte[] payload;

        private NoOpCallable(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public Void call() throws IOException {
            noops.incrementAndGet();
            return null;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {}

        private static final long serialVersionUID = 1L;
    }

    private class Stats implements Runnable {

        private boolean started;
        private boolean warmed;
        private Metrics start;
        double memoryA = Runtime.getRuntime().totalMemory();
        double memoryS = 0;
        int memoryCount = 1;

        public synchronized void clearStats() {
            start = new Metrics();
            memoryA = Runtime.getRuntime().totalMemory();
            memoryS = 0;
            memoryCount = 1;
            System.out.printf(
                    "%n%-7s   %-29s   %-20s   %8s   %14s%n", "", "          Calls rate", "JVM CPU utilization", "", "");
            System.out.printf(
                    "%-7s   %9s %9s %9s   %6s %6s %6s   %8s   %14s%n",
                    "Time", "cur", "all", "expect", "cur", "all", "expect", "Sys load", "Average Memory");
            System.out.printf(
                    "%7s   %9s %9s %9s   %6s %6s %6s   %8s   %14s%n",
                    "=======",
                    "=========",
                    "=========",
                    "=========",
                    "======",
                    "======",
                    "======",
                    "========",
                    "==============");
        }

        private synchronized void clientsStarted() {
            System.out.println("Resetting statistics after start...");
            clearStats();
            started = true;
        }

        @Override
        public void run() {
            clearStats();
            Metrics last = start;
            double expectedNoopsPerSecond = 1000.0 / config.clientIntervalMs * config.numClients;

            while (true) {
                long memory = Runtime.getRuntime().totalMemory();
                double memoryO = memoryA;
                memoryA += (memory - memoryO) / ++memoryCount;
                memoryS += (memory - memoryO) * (memory - memoryA);
                long next = last.time + 1000;
                long wait;
                while ((wait = next - System.currentTimeMillis()) > 0) {
                    try {
                        Thread.sleep(Math.min(wait, 100));
                    } catch (InterruptedException e) {
                        return;
                    }
                    memory = Runtime.getRuntime().totalMemory();
                    memoryO = memoryA;
                    memoryA += (memory - memoryO) / ++memoryCount;
                    memoryS += (memory - memoryO) * (memory - memoryA);
                }
                Metrics start = this.start;
                Metrics current = new Metrics();

                double noopsPerSecond0 = current.noopsPerSecond(start);
                double noopsPerSecond = current.noopsPerSecond(last);
                double vmLoad0 = current.vmLoad(start);
                double vmLoad = current.vmLoad(last);
                System.out.printf(
                        "%-4.1fmin   %7.1f/s %7.1f/s %7.1f/s   %6.2f %6.2f %6.2f   %8.2f   %7.1fkB ± %.1f %ddf   %s%n",
                        (current.uptime - start.uptime) / 60000.0,
                        noopsPerSecond,
                        noopsPerSecond0,
                        expectedNoopsPerSecond,
                        vmLoad,
                        vmLoad0,
                        vmLoad0 * expectedNoopsPerSecond / noopsPerSecond0,
                        operatingSystemMXBean.getSystemLoadAverage(),
                        memoryCount > 0 ? memoryA / 1024 : Double.NaN,
                        memoryCount > 1 ? Math.sqrt(memoryS / (memoryCount - 1)) / 1024 : Double.NaN,
                        memoryCount,
                        current.gcSummary(start));
                System.out.flush();
                last = current;
                if (started
                        && !warmed
                        && (config.warmup <= 0 || current.uptime - start.uptime > config.warmup * 1000L)) {
                    System.out.println("Warmup completed");
                    clearStats();
                    warmed = true;
                } else if (started
                        && warmed
                        && config.collect > 0
                        && current.uptime - start.uptime > config.collect * 1000L) {
                    if (config.file != null) {
                        try {
                            File f = new File(config.file);
                            PrintWriter pw;
                            if (!f.exists()) {
                                pw = new PrintWriter(new FileWriter(f));
                                pw.printf(
                                        "\"protocol\",\"io\",\"clients\",\"interval\",\"payload\",\"observedRate\","
                                                + "\"expectedRate\",\"vmLoad\",\"expectedVmLoad\",\"threads\","
                                                + "\"avgMemory\",\"stdMemory\",\"dfMemory\",\"maxMemory\",%s%n",
                                        current.gcTitles());
                            } else {
                                pw = new PrintWriter(new FileWriter(f, true));
                            }
                            try {
                                pw.printf(
                                        "\"%s\",\"%s\",%d,%d,%d,%.1f,%.1f,%.2f,%.2f,%d,%.2f,%.2f,%d,%.2f,%s%n",
                                        config.name,
                                        config.bio ? "blocking" : "non-blocking",
                                        config.numClients,
                                        config.clientIntervalMs,
                                        config.payload,
                                        noopsPerSecond0,
                                        expectedNoopsPerSecond,
                                        vmLoad0,
                                        vmLoad0 * expectedNoopsPerSecond / noopsPerSecond0,
                                        Thread.activeCount(),
                                        memoryCount > 0 ? memoryA / 1024 : Double.NaN,
                                        memoryCount > 1 ? Math.sqrt(memoryS / (memoryCount - 1)) / 1024 : Double.NaN,
                                        memoryCount,
                                        Runtime.getRuntime().maxMemory() / 1024.0,
                                        current.gcData(start));
                            } finally {
                                pw.close();
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    System.out.printf(
                            "%n\"protocol\",\"io\",\"clients\",\"interval\",\"payload\",\"observedRate\","
                                    + "\"expectedRate\",\"vmLoad\",\"expectedVmLoad\",\"threads\","
                                    + "\"avgMemory\",\"stdMemory\",\"dfMemory\",\"maxMemory\",%s%n"
                                    + "\"%s\",\"%s\",%d,%d,%d,%.1f,%.1f,%.2f,%.2f,%d,%.2f,%.2f,%d,%.2f,%s%n",
                            current.gcTitles(),
                            config.name,
                            config.bio ? "blocking" : "non-blocking",
                            config.numClients,
                            config.clientIntervalMs,
                            config.payload,
                            noopsPerSecond0,
                            expectedNoopsPerSecond,
                            vmLoad0,
                            vmLoad0 * expectedNoopsPerSecond / noopsPerSecond0,
                            Thread.activeCount(),
                            memoryCount > 0 ? memoryA / 1024 : Double.NaN,
                            memoryCount > 1 ? Math.sqrt(memoryS / (memoryCount - 1)) / 1024 : Double.NaN,
                            memoryCount,
                            Runtime.getRuntime().maxMemory() / 1024.0,
                            current.gcData(start));
                    System.exit(0);
                }
            }
        }
    }

    private static class GCStats {
        private final long count;
        private final long time;

        public GCStats(GarbageCollectorMXBean bean) {
            this.count = bean.getCollectionCount();
            this.time = bean.getCollectionTime();
        }
    }

    private class Metrics {
        private long time;
        private long noops;
        private long uptime;
        private @CheckForNull Long cpu;
        private Map<String, GCStats> gc;

        public Metrics() {
            time = System.currentTimeMillis();
            noops = NoOpCallable.noops.get();
            uptime = runtimeMXBean.getUptime();
            cpu = getProcessCpuTime();
            gc = new TreeMap<>();
            for (GarbageCollectorMXBean bean : garbageCollectorMXBeans) {
                this.gc.put(bean.getName(), new GCStats(bean));
            }
        }

        public long getTime() {
            return time;
        }

        public long getNoops() {
            return noops;
        }

        public long getUptime() {
            return uptime;
        }

        /**
         * Gets CPU load stats.
         * @return CPU Load stats. {@code null} if it cannot be determined.
         */
        @CheckForNull
        public Long getCpu() {
            return cpu;
        }

        public double noopsPerSecond(Metrics reference) {
            return (noops - reference.noops) * 1000.0 / (time - reference.time);
        }

        public double vmLoad(Metrics reference) {
            if (cpu == null || reference.cpu == null) {
                return Double.NaN;
            } else {
                return Math.min(99.0, (cpu - reference.cpu) / 1000000.0 / (uptime - reference.uptime));
            }
        }

        public String gcData(Metrics reference) {
            StringBuilder result = new StringBuilder();
            boolean first = true;
            for (GarbageCollectorMXBean g : garbageCollectorMXBeans) {
                String name = g.getName();
                GCStats s = reference.gc.get(name);
                GCStats x = gc.get(name);
                if (first) {
                    first = false;
                } else {
                    result.append(',');
                }
                result.append("\"").append(name).append("\",");
                if (x == null) {
                    result.append(0).append(',').append(0.0);
                } else if (s == null) {
                    result.append(x.count).append(',').append(x.time / 1000.0);
                } else {
                    result.append(x.count - s.count).append(',').append((x.time - s.time) / 1000.0);
                }
            }
            return result.toString();
        }

        public String gcTitles() {
            StringBuilder result = new StringBuilder();
            int i = 0;
            for (GarbageCollectorMXBean g : garbageCollectorMXBeans) {
                if (i > 0) {
                    result.append(",");
                }
                result.append("\"gc[").append(i).append("].name\",");
                result.append("\"gc[").append(i).append("].count\",");
                result.append("\"gc[").append(i).append("].time\"");
                i++;
            }
            return result.toString();
        }

        public String gcSummary(Metrics reference) {
            StringBuilder result = new StringBuilder();
            boolean first = true;
            for (GarbageCollectorMXBean g : garbageCollectorMXBeans) {
                String name = g.getName();
                GCStats s = reference.gc.get(name);
                GCStats x = gc.get(name);
                if (first) {
                    first = false;
                } else {
                    result.append(' ');
                }
                if (x == null) {
                    result.append(String.format("%s: %d / %.1fs", name, 0, 0.0));
                } else if (s == null) {
                    result.append(String.format("%s: %d / %.1fs", name, x.count, x.time / 1000.0));
                } else {
                    result.append(String.format("%s: %d / %.1fs", name, x.count - s.count, (x.time - s.time) / 1000.0));
                }
            }
            return result.toString();
        }
    }

    private class Acceptor implements IOHubReadyListener, IOHubRegistrationCallback {
        private final ServerSocketChannel channel;
        private final AtomicInteger clientCount = new AtomicInteger();
        public CompletableFuture<Void> registered = new CompletableFuture<>();
        private SelectionKey selectionKey;

        private Acceptor(ServerSocketChannel channel) {
            this.channel = channel;
        }

        @Override
        public void ready(boolean accept, boolean connect, boolean read, boolean write) {
            if (accept) {
                try {
                    final SocketChannel fromClient = channel.accept();
                    fromClient.socket().setKeepAlive(true);
                    fromClient.socket().setTcpNoDelay(true);
                    fromClient.configureBlocking(true);
                    executorService.submit(() -> {
                        try {
                            DataInputStream dis = new DataInputStream(SocketChannelStream.in(fromClient));
                            String header = dis.readUTF();
                            if (header.equals("Protocol:" + handler.getName())) {
                                handler.handle(fromClient.socket(), new HashMap<>(), serverListener)
                                        .get();
                                if (config.server && clientCount.incrementAndGet() >= config.numClients) {
                                    stats.clientsStarted();
                                }
                            } else {
                                fromClient.close();
                            }
                        } catch (IOException e) {
                            e.printStackTrace(System.err);
                        } catch (InterruptedException | ExecutionException e) {
                            e.printStackTrace();
                        }
                    });
                    acceptorHub.addInterestAccept(selectionKey);
                } catch (IOException e) {
                    e.printStackTrace(System.err);
                }
            }
        }

        @Override
        public void onRegistered(SelectionKey selectionKey) {
            this.selectionKey = selectionKey;
            SocketAddress localAddress;
            try {
                localAddress = serverSocketChannel.getLocalAddress();
                addr.complete(localAddress);
            } catch (IOException e) {
                addr.completeExceptionally(e);
                return;
            }
            try {
                System.out.println("Accepting connections on port " + localAddress);
            } catch (Exception e) {
                // ignore
            }
            registered.complete(null);
        }

        @Override
        public void onClosedChannel(ClosedChannelException e) {}
    }
}
