package org.jenkinsci.remoting.util;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.AccessController;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.jenkinsci.remoting.protocol.cert.DelegatingX509ExtendedTrustManager;
import org.jenkinsci.remoting.util.https.NoCheckTrustManager;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

@Restricted(NoExternalUse.class)
public final class SSLUtils {
    private SSLUtils() {}

    private static final Logger LOGGER = Logger.getLogger(SSLUtils.class.getName());

    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "File path is loaded from system properties.")
    static KeyStore getCacertsKeyStore()
            throws PrivilegedActionException, KeyStoreException, NoSuchProviderException, CertificateException,
                    NoSuchAlgorithmException, IOException {
        Map<String, String> properties =
                AccessController.doPrivileged((PrivilegedExceptionAction<Map<String, String>>) () -> {
                    Map<String, String> result = new HashMap<>();
                    result.put("trustStore", System.getProperty("javax.net.ssl.trustStore"));
                    result.put("javaHome", System.getProperty("java.home"));
                    result.put(
                            "trustStoreType",
                            System.getProperty("javax.net.ssl.trustStoreType", KeyStore.getDefaultType()));
                    result.put("trustStoreProvider", System.getProperty("javax.net.ssl.trustStoreProvider", ""));
                    result.put("trustStorePasswd", System.getProperty("javax.net.ssl.trustStorePassword", ""));
                    return result;
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
                    trustStoreFile = new File(javaHome + File.separator + "lib" + File.separator + "security"
                            + File.separator + "jssecacerts");
                    if ((trustStoreStream = getFileInputStream(trustStoreFile)) == null) {
                        trustStoreFile = new File(javaHome + File.separator + "lib" + File.separator + "security"
                                + File.separator + "cacerts");
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
                    Arrays.fill(trustStorePasswdChars, (char) 0);
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
        return AccessController.doPrivileged((PrivilegedExceptionAction<FileInputStream>) () -> {
            try {
                return file.exists() ? new FileInputStream(file) : null;
            } catch (FileNotFoundException e) {
                return null;
            }
        });
    }

    @CheckForNull
    @Restricted(NoExternalUse.class)
    public static SSLContext getSSLContext(List<X509Certificate> x509Certificates, boolean noCertificateCheck)
            throws PrivilegedActionException, KeyStoreException, NoSuchProviderException, CertificateException,
                    NoSuchAlgorithmException, IOException, KeyManagementException {
        SSLContext sslContext = null;
        if (noCertificateCheck) {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] {new NoCheckTrustManager()}, new SecureRandom());
        } else if (x509Certificates != null && !x509Certificates.isEmpty()) {
            KeyStore keyStore = getCacertsKeyStore();
            // load the keystore
            keyStore.load(null, null);
            int i = 0;
            for (X509Certificate c : x509Certificates) {
                keyStore.setCertificateEntry(String.format("alias-%d", i++), c);
            }
            // prepare the trust manager
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);
            // prepare the SSL context
            SSLContext ctx = SSLContext.getInstance("TLS");
            // now we have our custom socket factory
            ctx.init(null, trustManagerFactory.getTrustManagers(), null);
        }
        return sslContext;
    }

    @CheckForNull
    @Restricted(NoExternalUse.class)
    public static SSLSocketFactory getSSLSocketFactory(
            List<X509Certificate> x509Certificates, boolean noCertificateCheck)
            throws PrivilegedActionException, KeyStoreException, NoSuchProviderException, CertificateException,
                    NoSuchAlgorithmException, IOException, KeyManagementException {
        SSLContext sslContext = getSSLContext(x509Certificates, noCertificateCheck);
        return sslContext != null ? sslContext.getSocketFactory() : null;
    }

    @SuppressFBWarnings(value = "HARD_CODE_PASSWORD", justification = "Password doesn't need to be protected.")
    public static SSLContext createSSLContext(@CheckForNull DelegatingX509ExtendedTrustManager agentTrustManager)
            throws IOException {
        SSLContext context;
        // prepare our SSLContext
        try {
            context = SSLContext.getInstance("TLS");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime specification requires support for TLS algorithm", e);
        }
        char[] password = "password".toCharArray();
        KeyStore store;
        try {
            store = KeyStore.getInstance(KeyStore.getDefaultType());
        } catch (KeyStoreException e) {
            throw new IllegalStateException("Java runtime specification requires support for JKS key store", e);
        }
        try {
            store.load(null, password);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime specification requires support for JKS key store", e);
        } catch (CertificateException e) {
            throw new IllegalStateException("Empty keystore", e);
        }
        KeyManagerFactory kmf;
        try {
            kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Java runtime specification requires support for default key manager", e);
        }
        try {
            kmf.init(store, password);
        } catch (KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException e) {
            throw new IllegalStateException(e);
        }
        try {
            context.init(kmf.getKeyManagers(), new TrustManager[] {agentTrustManager}, null);
        } catch (KeyManagementException e) {
            throw new IllegalStateException(e);
        }
        return context;
    }
}
