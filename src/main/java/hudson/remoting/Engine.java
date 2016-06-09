/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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
package hudson.remoting;

import hudson.remoting.Channel.Mode;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.security.AccessController;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import javax.annotation.CheckForNull;
import javax.annotation.concurrent.NotThreadSafe;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.jenkinsci.remoting.engine.JnlpProtocol;
import org.jenkinsci.remoting.engine.JnlpProtocolFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static org.jenkinsci.remoting.engine.EngineUtil.readLine;

/**
 * Slave agent engine that proactively connects to Jenkins master.
 *
 * @author Kohsuke Kawaguchi
 */
@NotThreadSafe // the fields in this class should not be modified by multiple threads concurrently
public class Engine extends Thread {
    /**
     * Thread pool that sets {@link #CURRENT}.
     */
    private final ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactory() {
        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        public Thread newThread(final Runnable r) {
            Thread t = defaultFactory.newThread(new Runnable() {
                public void run() {
                    CURRENT.set(Engine.this);
                    r.run();
                }
            });
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * @deprecated
     *      Use {@link #events}.
     */
    @Deprecated
    public final EngineListener listener;

    private final EngineListenerSplitter events = new EngineListenerSplitter();

    /**
     * To make Jenkins more graceful against user error,
     * JNLP agent can try to connect to multiple possible Jenkins URLs.
     * This field specifies those candidate URLs, such as
     * "http://foo.bar/jenkins/".
     */
    private List<URL> candidateUrls;
    /**
     * The list of {@link X509Certificate} instances to trust when connecting to any of the {@link #candidateUrls}
     * or {@code null} to use the JVM default trust store.
     */
    private List<X509Certificate> candidateCertificates;

    /**
     * URL that points to Jenkins's tcp slave agent listener, like <tt>http://myhost/hudson/</tt>
     *
     * <p>
     * This value is determined from {@link #candidateUrls} after a successful connection.
     * Note that this URL <b>DOES NOT</b> have "tcpSlaveAgentListener" in it.
     */
    private URL hudsonUrl;

    private final String secretKey;
    public final String slaveName;
    private String credentials;
	private String proxyCredentials = System.getProperty("proxyCredentials");

    /**
     * See Main#tunnel in the jnlp-agent module for the details.
     */
    private String tunnel;

    private boolean noReconnect;

    private JarCache jarCache = new FileSystemJarCache(new File(System.getProperty("user.home"),".jenkins/cache/jars"),true);

    public Engine(EngineListener listener, List<URL> hudsonUrls, String secretKey, String slaveName) {
        this.listener = listener;
        this.events.add(listener);
        this.candidateUrls = hudsonUrls;
        this.secretKey = secretKey;
        this.slaveName = slaveName;
        if(candidateUrls.isEmpty())
            throw new IllegalArgumentException("No URLs given");
    }

    /**
     * Configures JAR caching for better performance.
     * @since 2.24
     */
    public void setJarCache(JarCache jarCache) {
        this.jarCache = jarCache;
    }

    public URL getHudsonUrl() {
        return hudsonUrl;
    }

    public void setTunnel(String tunnel) {
        this.tunnel = tunnel;
    }

    public void setCredentials(String creds) {
        this.credentials = creds;
    }

	public void setProxyCredentials(String proxyCredentials) {
		this.proxyCredentials = proxyCredentials;
	}

    public void setNoReconnect(boolean noReconnect) {
        this.noReconnect = noReconnect;
    }

    public void setCandidateCertificates(List<X509Certificate> candidateCertificates) {
        this.candidateCertificates = candidateCertificates == null
                ? null
                : new ArrayList<X509Certificate>(candidateCertificates);
    }

    public void addCandidateCertificate(X509Certificate certificate) {
        if (candidateCertificates == null) {
            candidateCertificates = new ArrayList<X509Certificate>();
        }
        candidateCertificates.add(certificate);
    }

    public void addListener(EngineListener el) {
        events.add(el);
    }

    public void removeListener(EngineListener el) {
        events.remove(el);
    }

    @SuppressWarnings({"ThrowableInstanceNeverThrown"})
    @Override
    public void run() {
        // Create the protocols that will be attempted to connect to the master.
        List<JnlpProtocol> protocols = JnlpProtocolFactory.createProtocols(secretKey, slaveName, events);

        try {
            boolean first = true;
            while(true) {
                if(first) {
                    first = false;
                } else {
                    if(noReconnect)
                        return; // exit
                }

                events.status("Locating server among " + candidateUrls);
                Throwable firstError=null;
                String host=null;
                String port=null;
                SSLSocketFactory sslSocketFactory = getSSLSocketFactory();

                for (URL url : candidateUrls) {
                    String s = url.toExternalForm();
                    if(!s.endsWith("/"))    s+='/';
                    URL salURL = new URL(s+"tcpSlaveAgentListener/");

                    // find out the TCP port
                    HttpURLConnection con = (HttpURLConnection)Util.openURLConnection(salURL, credentials, proxyCredentials, sslSocketFactory);
                    try {
                        try {
                            con.setConnectTimeout(30000);
                            con.setReadTimeout(60000);
                            con.connect();
                        } catch (IOException x) {
                            if (firstError == null) {
                                firstError = new IOException("Failed to connect to " + salURL + ": " + x.getMessage()).initCause(x);
                            }
                            continue;
                        }
                        port = con.getHeaderField("X-Hudson-JNLP-Port");
                        if(con.getResponseCode()!=200) {
                            if(firstError==null)
                                firstError = new Exception(salURL+" is invalid: "+con.getResponseCode()+" "+con.getResponseMessage());
                            continue;
                        }
                        if(port ==null) {
                            if(firstError==null)
                                firstError = new Exception(url+" is not Jenkins");
                            continue;
                        }
                        host = con.getHeaderField("X-Jenkins-JNLP-Host"); // controlled by hudson.TcpSlaveAgentListener.hostName
                        if (host == null) host=url.getHost();
                    } finally {
                        con.disconnect();
                    }

                    // this URL works. From now on, only try this URL
                    hudsonUrl = url;
                    firstError = null;
                    candidateUrls = Collections.singletonList(hudsonUrl);
                    break;
                }

                if(firstError!=null) {
                    events.error(firstError);
                    return;
                }

                events.status("Handshaking");
                Socket jnlpSocket = connect(host,port);
                ChannelBuilder channelBuilder = new ChannelBuilder("channel", executor)
                        .withJarCache(jarCache)
                        .withMode(Mode.BINARY);
                Channel channel = null;

                // Try available protocols.
                boolean triedAtLeastOneProtocol = false;
                for (JnlpProtocol protocol : protocols) {
                    if (!protocol.isEnabled()) {
                        events.status("Protocol " + protocol.getName() + " is not enabled, skipping");
                        continue;
                    }
                    triedAtLeastOneProtocol = true;
                    events.status("Trying protocol: " + protocol.getName());
                    try {
                        channel = protocol.establishChannel(jnlpSocket, channelBuilder);
                    } catch (IOException ioe) {
                        events.status("Protocol " + protocol.getName() + " failed to establish channel", ioe);
                    } catch (RuntimeException e) {
                        events.status("Protocol " + protocol.getName() + " encountered a runtime error", e);
                    } catch (Error e) {
                        events.status("Protocol " + protocol.getName() + " could not be completed due to an error", e);
                    } catch (Throwable e) {
                        events.status("Protocol " + protocol.getName() + " encountered an unexpected exception", e);
                    }

                    // On success do not try other protocols.
                    if (channel != null) {
                        break;
                    }

                    // On failure form a new connection.
                    jnlpSocket.close();
                    jnlpSocket = connect(host,port);
                }

                // If no protocol worked.
                if (channel == null) {
                    if (triedAtLeastOneProtocol) {
                        onConnectionRejected("None of the protocols were accepted");
                    } else {
                        onConnectionRejected("None of the protocols are enabled");
                        return; // exit
                    }
                    continue;
                }

                events.status("Connected");
                channel.join();
                events.status("Terminated");

                if(noReconnect)
                    return; // exit

                events.onDisconnect();

                // try to connect back to the server every 10 secs.
                waitForServerToBack();

                events.onReconnect();
            }
        } catch (Throwable e) {
            events.error(e);
        }
    }

    private void onConnectionRejected(String greeting) throws InterruptedException {
        events.error(new Exception("The server rejected the connection: " + greeting));
        Thread.sleep(10*1000);
    }

    /**
     * Connects to TCP slave host:port, with a few retries.
     */
    private Socket connect(String host, String port) throws IOException, InterruptedException {

        if(tunnel!=null) {
            String[] tokens = tunnel.split(":",3);
            if(tokens.length!=2)
                throw new IOException("Illegal tunneling parameter: "+tunnel);
            if(tokens[0].length()>0)    host = tokens[0];
            if(tokens[1].length()>0)    port = tokens[1];
        }

        String msg = "Connecting to " + host + ':' + port;
        events.status(msg);
        int retry = 1;
        while(true) {
            boolean isHttpProxy = false;
            InetSocketAddress targetAddress = null;
            try {
                Socket s = null;
                targetAddress = Util.getResolvedHttpProxyAddress(host, Integer.parseInt(port));

                if(targetAddress == null) {
                    targetAddress = new InetSocketAddress(host, Integer.parseInt(port));
                } else {
                    isHttpProxy = true;
                }

                s = new Socket();
                s.connect(targetAddress);

                s.setTcpNoDelay(true); // we'll do buffering by ourselves

                // set read time out to avoid infinite hang. the time out should be long enough so as not
                // to interfere with normal operation. the main purpose of this is that when the other peer dies
                // abruptly, we shouldn't hang forever, and at some point we should notice that the connection
                // is gone.
                s.setSoTimeout(SOCKET_TIMEOUT); // default is 30 mins. See PingThread for the ping interval

                if (isHttpProxy) {
                    String connectCommand = String.format("CONNECT %s:%s HTTP/1.1\r\nHost: %s\r\n\r\n", host, port, host);
                    s.getOutputStream().write(connectCommand.getBytes("UTF-8")); // TODO: internationalized domain names

                    BufferedInputStream is = new BufferedInputStream(s.getInputStream());
                    String line = readLine(is);
                    String[] responseLineParts = line.split(" ");
                    if(responseLineParts.length < 2 || !responseLineParts[1].equals("200"))
                        throw new IOException("Got a bad response from proxy: " + line);
                    while(!readLine(is).isEmpty()) {
                        // Do nothing, scrolling through headers returned from proxy
                    }
                }
                return s;
            } catch (IOException e) {
                if(retry++>10) {
                    String suffix = "";
                    if(isHttpProxy) {
                        suffix = " through proxy " + targetAddress.toString();
                    }
                    throw new IOException("Failed to connect to " + host + ':' + port + suffix, e);
                }
                Thread.sleep(1000*10);
                events.status(msg+" (retrying:"+retry+")",e);
            }
        }
    }

    /**
     * Waits for the server to come back.
     */
    private void waitForServerToBack() throws InterruptedException {
        Thread t = Thread.currentThread();
        String oldName = t.getName();
        SSLSocketFactory sslSocketFactory = null;
        try {
            sslSocketFactory = getSSLSocketFactory();
        } catch (Throwable e) {
            events.error(e);
        }
        try {
            int retries=0;
            while(true) {
                Thread.sleep(1000*10);
                try {
                    // Jenkins top page might be read-protected. see http://www.nabble.com/more-lenient-retry-logic-in-Engine.waitForServerToBack-td24703172.html
                    URL url = new URL(hudsonUrl, "tcpSlaveAgentListener/");

                    retries++;
                    t.setName(oldName+": trying "+url+" for "+retries+" times");

                    HttpURLConnection con = (HttpURLConnection)Util.openURLConnection(url, credentials, proxyCredentials, sslSocketFactory);
                    con.setConnectTimeout(5000);
                    con.setReadTimeout(5000);
                    con.connect();
                    if(con.getResponseCode()==200)
                        return;
                    LOGGER.info("Master isn't ready to talk to us. Will retry again: response code=" + con.getResponseCode());
                } catch (IOException e) {
                    // report the failure
                    LOGGER.log(INFO, "Failed to connect to the master. Will retry again",e);
                }
            }
        } finally {
            t.setName(oldName);
        }
    }

    /**
     * When invoked from within remoted {@link Callable} (that is,
     * from the thread that carries out the remote requests),
     * this method returns the {@link Engine} in which the remote operations
     * run.
     */
    public static Engine current() {
        return CURRENT.get();
    }

    private static final ThreadLocal<Engine> CURRENT = new ThreadLocal<Engine>();

    private static final Logger LOGGER = Logger.getLogger(Engine.class.getName());

    private static KeyStore getCacertsKeyStore()
            throws PrivilegedActionException, KeyStoreException, NoSuchProviderException, CertificateException,
            NoSuchAlgorithmException, IOException {
        Map<String, String> properties = AccessController.doPrivileged(
                new PrivilegedExceptionAction<Map<String, String>>() {
                    public Map<String, String> run() throws Exception {
                        Map<String, String> result = new HashMap<String, String>();
                        result.put("trustStore", System.getProperty("javax.net.ssl.trustStore"));
                        result.put("javaHome", System.getProperty("java.home"));
                        result.put("trustStoreType",
                                System.getProperty("javax.net.ssl.trustStoreType", KeyStore.getDefaultType()));
                        result.put("trustStoreProvider", System.getProperty("javax.net.ssl.trustStoreProvider", ""));
                        result.put("trustStorePasswd", System.getProperty("javax.net.ssl.trustStorePassword", ""));
                        return result;
                    }
                });
        KeyStore keystore = null;

        FileInputStream trustStoreStream = null;
        try {
            String trustStore = properties.get("trustStore");
            if (!"NONE".equals(trustStore)) {
                File trustStoreFile;
                if (trustStore != null) {
                    trustStoreFile = new File(trustStore);
                    trustStoreStream = getFileInputStream(trustStoreFile);
                } else {
                    String javaHome = properties.get("javaHome");
                    trustStoreFile = new File(
                            javaHome + File.separator + "lib" + File.separator + "security" + File.separator
                                    + "jssecacerts");
                    if ((trustStoreStream = getFileInputStream(trustStoreFile)) == null) {
                        trustStoreFile = new File(
                                javaHome + File.separator + "lib" + File.separator + "security" + File.separator
                                        + "cacerts");
                        trustStoreStream = getFileInputStream(trustStoreFile);
                    }
                }

                if (trustStoreStream != null) {
                    trustStore = trustStoreFile.getPath();
                } else {
                    trustStore = "No File Available, using empty keystore.";
                }
            }

            String trustStoreType = properties.get("trustStoreType");
            String trustStoreProvider = properties.get("trustStoreProvider");
            LOGGER.log(Level.FINE, "trustStore is: {0}", trustStore);
            LOGGER.log(Level.FINE, "trustStore type is: {0}", trustStoreType);
            LOGGER.log(Level.FINE, "trustStore provider is: {0}", trustStoreProvider);

            if (trustStoreType.length() != 0) {
                LOGGER.log(Level.FINE, "init truststore");

                if (trustStoreProvider.length() == 0) {
                    keystore = KeyStore.getInstance(trustStoreType);
                } else {
                    keystore = KeyStore.getInstance(trustStoreType, trustStoreProvider);
                }

                char[] trustStorePasswdChars = null;
                String trustStorePasswd = properties.get("trustStorePasswd");
                if (trustStorePasswd.length() != 0) {
                    trustStorePasswdChars = trustStorePasswd.toCharArray();
                }

                keystore.load(trustStoreStream, trustStorePasswdChars);
                if (trustStorePasswdChars != null) {
                    for (int i = 0; i < trustStorePasswdChars.length; ++i) {
                        trustStorePasswdChars[i] = 0;
                    }
                }
            }
        } finally {
            if (trustStoreStream != null) {
                trustStoreStream.close();
            }
        }

        return keystore;
    }

    @CheckForNull
    private static FileInputStream getFileInputStream(final File file) throws PrivilegedActionException {
        return AccessController.doPrivileged(new PrivilegedExceptionAction<FileInputStream>() {
            public FileInputStream run() throws Exception {
                try {
                    return file.exists() ? new FileInputStream(file) : null;
                } catch (FileNotFoundException e) {
                    return null;
                }
            }
        });
    }

    private SSLSocketFactory getSSLSocketFactory()
            throws PrivilegedActionException, KeyStoreException, NoSuchProviderException, CertificateException,
            NoSuchAlgorithmException, IOException, KeyManagementException {
        SSLSocketFactory sslSocketFactory = null;
        if (candidateCertificates != null && !candidateCertificates.isEmpty()) {
            KeyStore keyStore = getCacertsKeyStore();
            // load the keystore
            keyStore.load(null, null);
            int i = 0;
            for (X509Certificate c : candidateCertificates) {
                keyStore.setCertificateEntry(String.format("alias-%d", i++), c);
            }
            // prepare the trust manager
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            // prepare the SSL context
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustManagerFactory.getTrustManagers(), null);
            // now we have our custom socket factory
            sslSocketFactory = ctx.getSocketFactory();
        }
        return sslSocketFactory;
    }
    //a read() call on the SocketInputStream associated with underlying Socket will block for only this amount of time
    static final int SOCKET_TIMEOUT = Integer.getInteger(Engine.class.getName()+".socketTimeout",30*60*1000);
    /**
     * @deprecated Use {@link JnlpProtocol#GREETING_SUCCESS}.
     */
    @Deprecated
    public static final String GREETING_SUCCESS = JnlpProtocol.GREETING_SUCCESS;
}
