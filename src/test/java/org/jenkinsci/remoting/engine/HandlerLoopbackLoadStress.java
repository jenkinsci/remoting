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

import com.google.common.util.concurrent.SettableFuture;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.SocketChannelStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
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
import java.security.KeyManagementException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.HashMap;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.X509Extension;
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
import org.jenkinsci.remoting.protocol.cert.PublicKeyMatchingX509ExtendedTrustManager;

public class HandlerLoopbackLoadStress {

    private static final BouncyCastleProvider BOUNCY_CASTLE_PROVIDER = new BouncyCastleProvider();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private final Timer[] timer = createTimers();
    private final JnlpConnectionStateListener serverListener = new MyJnlpConnectionStateListener(Channel.Mode.NEGOTIATE);
    private final JnlpConnectionStateListener clientListener = new MyJnlpConnectionStateListener(Channel.Mode.BINARY);

    private Timer[] createTimers() {
        Timer[] result = new Timer[Runtime.getRuntime().availableProcessors()];
        for (int i = 0; i < result.length; i++) {
            result[i] = new Timer(true);
        }
        return result;
    }

    private final IOHub hub;
    private final NioChannelHub legacyHub;

    private final SSLContext context;

    private final ServerSocketChannel serverSocketChannel;

    private final Acceptor acceptor;

    private final KeyPair keyPair;
    private final X509Certificate certificate;

    private final JnlpProtocolHandler<? extends JnlpConnectionState> handler;

    private final SettableFuture<SocketAddress> addr = SettableFuture.create();
    private final Random entropy = new Random();

    public HandlerLoopbackLoadStress(boolean nio, String name)
            throws IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException,
            UnrecoverableKeyException, KeyManagementException, OperatorCreationException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048); // maximum supported by JVM with export restrictions
        keyPair = gen.generateKeyPair();

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
                subject,
                BigInteger.ONE,
                firstDate,
                lastDate,
                subject,
                subjectPublicKeyInfo
        );

        JcaX509ExtensionUtils instance = new JcaX509ExtensionUtils();

        certGen.addExtension(X509Extension.subjectKeyIdentifier,
                false,
                instance.createSubjectKeyIdentifier(subjectPublicKeyInfo)
        );

        ContentSigner signer = new JcaContentSignerBuilder("SHA1withRSA")
                .setProvider(BOUNCY_CASTLE_PROVIDER)
                .build(keyPair.getPrivate());

        certificate = new JcaX509CertificateConverter()
                .setProvider(BOUNCY_CASTLE_PROVIDER)
                .getCertificate(certGen.build(signer));

        char[] password = "password".toCharArray();

        KeyStore store = KeyStore.getInstance("jks");
        store.load(null, password);
        store.setKeyEntry("alias", keyPair.getPrivate(), password, new Certificate[]{certificate});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, password);

        context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(),
                new TrustManager[]{new PublicKeyMatchingX509ExtendedTrustManager(keyPair.getPublic())}, null);

        hub = IOHub.create(executorService);
        legacyHub = new NioChannelHub(executorService);
        executorService.submit(legacyHub);
        serverSocketChannel = ServerSocketChannel.open();

        JnlpProtocolHandler handler = null;
        for (JnlpProtocolHandler h : new JnlpProtocolHandlerFactory(executorService)
                .withNioChannelHub(legacyHub)
                .withIOHub(hub)
                .withSSLContext(context)
                .withClientDatabase(new JnlpClientDatabase() {
                    @Override
                    public boolean exists(String clientName) {
                        return true;
                    }

                    @Override
                    public String getSecretOf(@Nonnull String clientName) {
                        return "SECRET" + clientName;
                    }
                })
                .withSSLClientAuthRequired(false)
                .handlers()) {
            if (name.equals(h.getName())) {
                handler = h;
                break;
            }
        }
        if (handler == null) {
            throw new RuntimeException("Unknown handler: " + name);
        }
        this.handler = handler;

        acceptor = new Acceptor(serverSocketChannel, nio);
    }

    private SocketAddress startServer() throws IOException, ExecutionException, InterruptedException {
        serverSocketChannel.bind(new InetSocketAddress(0));
        serverSocketChannel.configureBlocking(false);
        hub.register(serverSocketChannel, acceptor, true, false, false, false, acceptor);
        return addr.get();
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
        public void checkRoles(RoleChecker checker) throws SecurityException {

        }
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

        }
    }

    public class Acceptor implements IOHubReadyListener, IOHubRegistrationCallback {
        private final ServerSocketChannel channel;
        private SelectionKey selectionKey;
        private final boolean nio;

        public Acceptor(ServerSocketChannel channel, boolean nio) {
            this.channel = channel;
            this.nio = nio;
        }

        @Override
        public void ready(boolean accept, boolean connect, boolean read, boolean write) {
            if (accept) {
                try {
                    final SocketChannel fromClient = channel.accept();
                    fromClient.socket().setKeepAlive(true);
                    fromClient.socket().setTcpNoDelay(true);
                    fromClient.configureBlocking(true);
                    executorService.submit(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                DataInputStream dis = new DataInputStream(SocketChannelStream.in(fromClient));
                                String header = dis.readUTF();
                                if (header.equals("Protocol:" + handler.getName())) {
                                    handler.handle(fromClient.socket(), new HashMap<String, String>(), serverListener).get();
                                    System.out.println("Accepted connection from " + fromClient.getRemoteAddress());
                                } else {
                                    fromClient.close();
                                }
                            } catch (IOException e) {
                                e.printStackTrace(System.err);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            } catch (ExecutionException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                    hub.addInterestAccept(selectionKey);
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
                addr.set(localAddress);
            } catch (IOException e) {
                addr.setException(e);
                return;
            }
            try {
                System.out.println("  Accepting connections on port " + localAddress);
            } catch (Exception e) {
                // ignore
            }
        }

        @Override
        public void onClosedChannel(ClosedChannelException e) {

        }
    }

    public static void main(String[] args) throws Exception {
        String name = args.length >= 1 ? args[0] : "JNLP4-connect";
        int numClients = args.length >= 2 ? Integer.parseInt(args[1]) : 100;
        int clientIntervalMs = args.length >= 3 ? Integer.parseInt(args[2]) : 100;
        int payload = args.length >= 4 ? Integer.parseInt(args[3]) : -1;
        boolean nio = args.length < 5 || !"bio".equals(args[4].toLowerCase());
        final double expectNoopsPerSecond = 1000.0 / clientIntervalMs * numClients;
        final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        final OperatingSystemMXBean operatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean();
        final Method getProcessCpuTime = getProcessCpuTime(operatingSystemMXBean);
        System.out.printf("Starting stress test of %s with %d clients making calls (payload %d bytes) every %dms (%.1f/sec) to give a "
                                + "total expected rate of %.1f/sec%n",
                        name, numClients, payload, clientIntervalMs, 1000.0 / clientIntervalMs, expectNoopsPerSecond);
        System.out.printf("Server using %s%n", nio ? "Non-blocking I/O" : "Reader thread per client I/O");
        HandlerLoopbackLoadStress stress = new HandlerLoopbackLoadStress(nio, name);
        stress.hub.execute(new Runnable() {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                long last = start;
                long initialNoops = NoOpCallable.noops.get();
                long previousNoops = NoOpCallable.noops.get();
                long uptime = runtimeMXBean.getUptime();
                long cpu = 0;
                while (true) {
                    long next = last + 1000;
                    long wait;
                    while ((wait = next - System.currentTimeMillis()) > 0) {
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    long now = System.currentTimeMillis();
                    long currentNoops = NoOpCallable.noops.get();
                    double noopsPerSecond = (currentNoops - initialNoops) * 1000.0 / (now - start);
                    double instantNoopsPerSecond = (currentNoops - previousNoops) * 1000.0 / (now - last);
                    double vmLoad;
                    double vmLoad0;
                    if (getProcessCpuTime == null) {
                        vmLoad = Double.NaN;
                        vmLoad0 = Double.NaN;
                    } else {
                        Object r = null;
                        try {
                            r = getProcessCpuTime.invoke(operatingSystemMXBean);
                        } catch (IllegalAccessException e) {
                            r = null;
                        } catch (InvocationTargetException e) {
                            r = null;
                        }
                        if (r instanceof Number && ((Number) r).longValue() >= 0) {
                            long ut = runtimeMXBean.getUptime();
                            double elapsed = TimeUnit.NANOSECONDS.toMillis(((Number) r).longValue() - cpu);
                            vmLoad = Math.min(99.0, elapsed / (ut - uptime));
                            vmLoad0 = Math.min(99.0, TimeUnit.NANOSECONDS.toMillis(((Number) r).longValue()) * 1.0 / ut);
                            uptime = ut;
                            cpu = ((Number) r).longValue();
                        } else {
                            vmLoad = Double.NaN;
                            vmLoad0 = Double.NaN;
                        }
                    }
                    System.out.printf("%nTotal rate %.1f/sec, instant %.1f/sec, expect %.1f/sec sys.load %.1f vm.load %.1f (%.1f)%n", noopsPerSecond,
                            instantNoopsPerSecond, expectNoopsPerSecond, operatingSystemMXBean.getSystemLoadAverage(), vmLoad, vmLoad0);
                    System.out.flush();
                    last = now;
                    previousNoops = currentNoops;
                }
            }
        });
        SocketAddress serverAddress = stress.startServer();
        for (int i = 0; i < numClients; i++) {
            Thread.sleep(10);
            if (i % 10 == 0) {
                System.out.println("Starting client " + i);
            }
            stress.startClient(i, serverAddress, clientIntervalMs, payload);
        }
        System.out.println("All clients started");

    }

    private static Method getProcessCpuTime(OperatingSystemMXBean operatingSystemMXBean) {
        Method getProcessCpuTime;
        try {
            getProcessCpuTime = operatingSystemMXBean.getClass().getMethod("getProcessCpuTime");
            getProcessCpuTime.setAccessible(true);
        } catch (ClassCastException e) {
            getProcessCpuTime = null;
        } catch (NoSuchMethodException e) {
            getProcessCpuTime = null;
        }
        return getProcessCpuTime;
    }

    private void startClient(int n, SocketAddress serverAddress, final int clientIntervalMs, final int payloadSize)
            throws IOException, ExecutionException, InterruptedException {
        SocketChannel toServer = SocketChannel.open();
        toServer.socket().setKeepAlive(true);
        toServer.socket().setTcpNoDelay(true);
        toServer.configureBlocking(true);
        toServer.connect(serverAddress);
        HashMap<String, String> headers = new HashMap<String, String>();
        headers.put(JnlpConnectionState.CLIENT_NAME_KEY, "client-" + n);
        headers.put(JnlpConnectionState.SECRET_KEY, "SECRETclient-" + n);
        final Channel clientChannel = handler.connect(toServer.socket(), headers, clientListener).get();
        timer[n % timer.length].scheduleAtFixedRate(new TimerTask() {
            private NoOpCallable callable = new NoOpCallable(payloadSize == -1 ? null : new byte[payloadSize]);
            long start = System.currentTimeMillis();
            int times = 0;

            @Override
            public void run() {
                try {
                    long start = System.currentTimeMillis();
                    clientChannel.call(callable);
                    times++;
                    if (times % 1000 == 0) {
                        System.out.println(String.format("  %s has run %d No-op callables. Rate %.1f/s expect %.1f/s",
                                clientChannel.getName(), times,
                                times * 1000.0 / (System.currentTimeMillis() - this.start), 1000.0 / clientIntervalMs));
                    }
                    long duration = System.currentTimeMillis() - start;
                    if (duration > 250L) {
                        System.err.println(
                                String.format("  %s took %dms to complete a callable", clientChannel.getName(),
                                        duration));
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.err);
                    IOUtils.closeQuietly(clientChannel);
                    cancel();
                }
            }
        }, entropy.nextInt(clientIntervalMs), clientIntervalMs);
    }

}
