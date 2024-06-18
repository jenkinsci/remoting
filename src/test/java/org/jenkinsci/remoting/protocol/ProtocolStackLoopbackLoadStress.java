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
package org.jenkinsci.remoting.protocol;

import hudson.remoting.Callable;
import hudson.remoting.Channel;
import java.io.IOException;
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
import java.util.Map;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
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
import org.jenkinsci.remoting.protocol.cert.PublicKeyMatchingX509ExtendedTrustManager;
import org.jenkinsci.remoting.protocol.impl.AckFilterLayer;
import org.jenkinsci.remoting.protocol.impl.BIONetworkLayer;
import org.jenkinsci.remoting.protocol.impl.ChannelApplicationLayer;
import org.jenkinsci.remoting.protocol.impl.ConnectionHeadersFilterLayer;
import org.jenkinsci.remoting.protocol.impl.NIONetworkLayer;
import org.jenkinsci.remoting.protocol.impl.SSLEngineFilterLayer;

public class ProtocolStackLoopbackLoadStress {

    private static final BouncyCastleProvider BOUNCY_CASTLE_PROVIDER = new BouncyCastleProvider();
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private final Timer[] timer = createTimers();

    private Timer[] createTimers() {
        Timer[] result = new Timer[Runtime.getRuntime().availableProcessors()];
        for (int i = 0; i < result.length; i++) {
            result[i] = new Timer(true);
        }
        return result;
    }

    private final IOHub hub;

    private final SSLContext context;

    private final ServerSocketChannel serverSocketChannel;

    private final Acceptor acceptor;

    private final CompletableFuture<SocketAddress> addr = new CompletableFuture<>();
    private final Random entropy = new Random();

    public ProtocolStackLoopbackLoadStress(boolean nio, boolean ssl)
            throws IOException, NoSuchAlgorithmException, CertificateException, KeyStoreException,
                    UnrecoverableKeyException, KeyManagementException, OperatorCreationException {
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

        context = SSLContext.getInstance("TLS");
        context.init(
                kmf.getKeyManagers(),
                new TrustManager[] {new PublicKeyMatchingX509ExtendedTrustManager(keyPair.getPublic())},
                null);

        hub = IOHub.create(executorService);
        serverSocketChannel = ServerSocketChannel.open();
        acceptor = new Acceptor(serverSocketChannel, nio, ssl);
    }

    private SocketAddress startServer() throws IOException, ExecutionException, InterruptedException {
        serverSocketChannel.bind(new InetSocketAddress(0));
        serverSocketChannel.configureBlocking(false);
        hub.register(serverSocketChannel, acceptor, true, false, false, false, acceptor);
        return addr.get();
    }

    private static class NoOpCallable implements Callable<Void, IOException> {

        private static final AtomicLong noops = new AtomicLong();

        @Override
        public Void call() throws IOException {
            noops.incrementAndGet();
            return null;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {}

        private static final long serialVersionUID = 1L;
    }

    public class Acceptor implements IOHubReadyListener, IOHubRegistrationCallback {
        private final ServerSocketChannel channel;
        private SelectionKey selectionKey;
        private final boolean nio;
        private final boolean ssl;

        public Acceptor(ServerSocketChannel channel, boolean nio, boolean ssl) {
            this.channel = channel;
            this.nio = nio;
            this.ssl = ssl;
        }

        @Override
        public void ready(boolean accept, boolean connect, boolean read, boolean write) {
            if (accept) {
                try {
                    final SocketChannel fromClient = channel.accept();
                    SSLEngine sslEngine = context.createSSLEngine();
                    sslEngine.setUseClientMode(false);
                    sslEngine.setNeedClientAuth(true);
                    final ProtocolStack<Future<Channel>> channelFromClient = ProtocolStack.on(
                                    nio
                                            ? new NIONetworkLayer(hub, fromClient, fromClient)
                                            : new BIONetworkLayer(hub, fromClient, fromClient))
                            .named(String.format("Serving client %s", fromClient.toString()))
                            .filter(new AckFilterLayer())
                            .filter(ssl ? new SSLEngineFilterLayer(sslEngine, null) : null)
                            .filter(new ConnectionHeadersFilterLayer(Map.of("id", "server"), headers -> {}))
                            .build(new ChannelApplicationLayer(executorService, null));
                    hub.execute(() -> {
                        try {
                            channelFromClient.get();
                            System.out.println("Accepted connection from " + fromClient.getRemoteAddress());
                        } catch (IOException e) {
                            e.printStackTrace(System.err);
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
                addr.complete(localAddress);
            } catch (IOException e) {
                addr.completeExceptionally(e);
                return;
            }
            try {
                System.out.println("  Accepting connections on port " + localAddress);
            } catch (Exception e) {
                // ignore
            }
        }

        @Override
        public void onClosedChannel(ClosedChannelException e) {}
    }

    public static void main(String[] args) throws Exception {
        int numClients = args.length >= 1 ? Integer.parseInt(args[0]) : 100;
        int clientIntervalMs = args.length >= 2 ? Integer.parseInt(args[1]) : 100;
        boolean nio = args.length < 3 || !"bio".equalsIgnoreCase(args[2]);
        final boolean ssl = args.length < 4 || !"cleartext".equalsIgnoreCase(args[3]);
        final double expectNoopsPerSecond = 1000.0 / clientIntervalMs * numClients;
        System.out.printf(
                "Starting stress test with %d clients making calls every %dms (%.1f/sec) to give a total expected rate of %.1f/sec%n",
                numClients, clientIntervalMs, 1000.0 / clientIntervalMs, expectNoopsPerSecond);
        System.out.printf("Server using %s%n", nio ? "Non-blocking I/O" : "Reader thread per client I/O");
        System.out.printf("Protocol stack using %s%n", ssl ? "TLS encrypted transport" : "cleartext transport");
        ProtocolStackLoopbackLoadStress stress = new ProtocolStackLoopbackLoadStress(nio, ssl);
        stress.hub.execute(() -> {
            long start = System.currentTimeMillis();
            long last = start;
            long initialNoops = NoOpCallable.noops.get();
            long previousNoops = NoOpCallable.noops.get();
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
                System.out.printf(
                        "%nTotal rate %.1f/sec, instant %.1f/sec, expect %.1f/sec%n",
                        noopsPerSecond, instantNoopsPerSecond, expectNoopsPerSecond);
                System.out.flush();
                last = now;
                previousNoops = currentNoops;
            }
        });
        SocketAddress serverAddress = stress.startServer();
        for (int i = 0; i < numClients; i++) {
            Thread.sleep(10);
            if (i % 10 == 0) {
                System.out.println("Starting client " + i);
            }
            stress.startClient(i, serverAddress, clientIntervalMs, ssl);
        }
        System.out.println("All clients started");
    }

    private void startClient(int n, SocketAddress serverAddress, final int clientIntervalMs, boolean ssl)
            throws IOException, ExecutionException, InterruptedException {
        SocketChannel toServer = SocketChannel.open();
        toServer.connect(serverAddress);
        SSLEngine sslEngine = context.createSSLEngine();
        sslEngine.setUseClientMode(true);
        final Channel clientChannel = ProtocolStack.on(new NIONetworkLayer(hub, toServer, toServer))
                .named(String.format("Client %d:  %s -> %s", n, toServer.getLocalAddress(), serverAddress))
                .filter(new AckFilterLayer())
                .filter(ssl ? new SSLEngineFilterLayer(sslEngine, null) : null)
                .filter(new ConnectionHeadersFilterLayer(Map.of("id", "client"), headers -> {}))
                .build(new ChannelApplicationLayer(executorService, null))
                .get()
                .get();
        timer[n % timer.length].scheduleAtFixedRate(
                new TimerTask() {
                    private NoOpCallable callable = new NoOpCallable();
                    long start = System.currentTimeMillis();
                    int times = 0;

                    @Override
                    public void run() {
                        try {
                            long start = System.currentTimeMillis();
                            clientChannel.call(callable);
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
                        } catch (Exception e) {
                            e.printStackTrace(System.err);
                            IOUtils.closeQuietly(clientChannel);
                            cancel();
                        }
                    }
                },
                entropy.nextInt(clientIntervalMs),
                clientIntervalMs);
    }
}
