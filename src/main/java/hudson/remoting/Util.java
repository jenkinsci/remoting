package hudson.remoting;

import org.jvnet.animal_sniffer.IgnoreJRERequirement;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLConnection;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;
import javax.annotation.Nonnull;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.nio.file.Files;
import java.util.Iterator;

/**
 * Misc. I/O utilities
 *
 * @author Kohsuke Kawaguchi
 */
class Util {
    /**
     * Gets the file name portion from a qualified '/'-separate resource path name.
     *
     * Acts like basename(1)
     */
    static String getBaseName(String path) {
        return path.substring(path.lastIndexOf('/')+1);
    }

    static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        copy(in,baos);
        return baos.toByteArray();
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] buf = new byte[8192];
            int len;
            while((len=in.read(buf))>0)
                out.write(buf,0,len);
        } finally {
            in.close();
        }
    }

    @Nonnull
    static File makeResource(String name, byte[] image) throws IOException {
        File tmpFile = createTempDir();
        File resource = new File(tmpFile, name);
        resource.getParentFile().mkdirs();

        FileOutputStream fos = new FileOutputStream(resource);
        try {
            fos.write(image);
        } finally {
            fos.close();
        }

        deleteDirectoryOnExit(tmpFile);

        return resource;
    }

    static File createTempDir() throws IOException {
    	// work around sun bug 6325169 on windows
    	// see http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6325169
        int nRetry=0;
        while (true) {
            try {
                File tmpFile = File.createTempFile("jenkins-remoting", "");
                tmpFile.delete();
                tmpFile.mkdir();
                return tmpFile;
            } catch (IOException e) {
                if (nRetry++ < 100){
                    continue;
                }
                IOException nioe = new IOException("failed to create temp directory at default location, most probably at: "+System.getProperty("java.io.tmpdir"));
                nioe.initCause(e);
                throw nioe;
            }
        }
    }

    /** Instructs Java to recursively delete the given directory (dir) and its contents when the JVM exits.
     *  @param dir File  customer  representing directory to delete. If this file argument is not a directory, it will still
     *  be deleted. <p>
     *  The method works in Java 1.3, Java 1.4, Java 5.0 and Java 6.0; but it does not work with some early Java 6.0 versions
     *  See http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6437591
     */
    static void deleteDirectoryOnExit(final File dir) {
        // Delete this on exit.  Delete on exit requests are processed in REVERSE order
        dir.deleteOnExit();

        // If it's a directory, visit its children.  This recursive walk has to be done AFTER calling deleteOnExit
        // on the directory itself because Java deletes the files to be deleted on exit in reverse order.
        if (dir.isDirectory()) {
            File[] childFiles = dir.listFiles();
            if (childFiles != null) { // listFiles may return null if there's an IO error
                for (File f: childFiles) { deleteDirectoryOnExit(f); }
            }
        }
    }

    static String indent(String s) {
        return "    " + s.trim().replace("\n", "\n    ");
    }

    /**
     * Check if given URL is in the exclusion list defined by the no_proxy environment variable.
     * On most *NIX system wildcards are not supported but if one top domain is added, all related subdomains will also
     * be ignored. Both "mit.edu" and ".mit.edu" are valid syntax.
     * http://www.gnu.org/software/wget/manual/html_node/Proxies.html
     *
     * Regexp:
     * - \Q and \E: https://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html
     * - To match IPV4/IPV/FQDN: Regular Expressions Cookbook, 2nd Edition (ISBN: 9781449327453)
     *
     * Warning: this method won't match shortened representation of IPV6 address
     */
    static boolean inNoProxyEnvVar(@Nonnull  String host) {
        String noProxy = System.getenv("no_proxy");
        if (noProxy != null) {
            noProxy = noProxy.trim()
                    // Remove spaces
                    .replaceAll("\\s+", "")
                    // Convert .foobar.com to foobar.com
                    .replaceAll("((?<=^|,)\\.)*(([a-z0-9]+(-[a-z0-9]+)*\\.)+[a-z]{2,})(?=($|,))", "$2");

            if (!noProxy.isEmpty()) {
                // IPV4 and IPV6
                if (host.matches("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$") || host.matches("^(?:[a-fA-F0-9]{1,4}:){7}[a-fA-F0-9]{1,4}$")) {
                    return noProxy.matches(".*(^|,)\\Q" + host + "\\E($|,).*");
                }
                else {
                    int depth = 0;
                    // Loop while we have a valid domain name: acme.com
                    // We add a safeguard to avoid a case where the host would always be valid because the regex would
                    // for example fail to remove subdomains.
                    // According to Wikipedia (no RFC defines it), 128 is the max number of subdivision for a valid FQDN:
                    // https://en.wikipedia.org/wiki/Subdomain#Overview
                    while (host.matches("^([a-z0-9]+(-[a-z0-9]+)*\\.)+[a-z]{2,}$") && depth < 128) {
                        ++depth;
                        // Check if the no_proxy contains the host
                        if (noProxy.matches(".*(^|,)\\Q" + host + "\\E($|,).*"))
                            return true;
                        // Remove first subdomain: master.jenkins.acme.com -> jenkins.acme.com
                        else
                            host = host.replaceFirst("^[a-z0-9]+(-[a-z0-9]+)*\\.", "");
                    }
                }
            }
        }

        return false;
    }

    /**
     * Gets URL connection.
     * If http_proxy environment variable exists,  the connection uses the proxy.
     * Credentials can be passed e.g. to support running Jenkins behind a (reverse) proxy requiring authorization
     */
    static URLConnection openURLConnection(URL url, String credentials, String proxyCredentials, SSLSocketFactory sslSocketFactory) throws IOException {
        String httpProxy = null;
        // If http.proxyHost property exists, openConnection() uses it.
        if (System.getProperty("http.proxyHost") == null) {
            httpProxy = System.getenv("http_proxy");
        }
        URLConnection con = null;
        if (httpProxy != null && "http".equals(url.getProtocol()) && !inNoProxyEnvVar(url.getHost())) {
            try {
                URL proxyUrl = new URL(httpProxy);
                SocketAddress addr = new InetSocketAddress(proxyUrl.getHost(), proxyUrl.getPort());
                Proxy proxy = new Proxy(Proxy.Type.HTTP, addr);
                con = url.openConnection(proxy);
            } catch (MalformedURLException e) {
                System.err.println("Not use http_proxy property or environment variable which is invalid: "+e.getMessage());
                con = url.openConnection();
            }
        } else {
            con = url.openConnection();
        }
        if (credentials != null) {
            String encoding = Base64.encode(credentials.getBytes("UTF-8"));
            con.setRequestProperty("Authorization", "Basic " + encoding);
        }
        if (proxyCredentials != null) {
            String encoding = Base64.encode(proxyCredentials.getBytes("UTF-8"));
            con.setRequestProperty("Proxy-Authorization", "Basic " + encoding);
        }
        if (con instanceof HttpsURLConnection && sslSocketFactory != null) {
            ((HttpsURLConnection) con).setSSLSocketFactory(sslSocketFactory);
        }
        return con;
    }

    /**
     * Gets URL connection.
     * If http_proxy environment variable exists,  the connection uses the proxy.
     * Credentials can be passed e.g. to support running Jenkins behind a (reverse) proxy requiring authorization
     */
    static URLConnection openURLConnection(URL url, String credentials, String proxyCredentials) throws IOException {
        return openURLConnection(url, credentials, proxyCredentials, null);
    }

    /**
     * Gets URL connection.
     * If http_proxy environment variable exists,  the connection uses the proxy.
     */
    static URLConnection openURLConnection(URL url) throws IOException {
        return openURLConnection(url, null, null, null);
    }

    @IgnoreJRERequirement @SuppressWarnings("Since15")
    static void mkdirs(@Nonnull File file) throws IOException {
        if (file.isDirectory()) return;

        try {
            Class.forName("java.nio.file.Files");
            Files.createDirectories(file.toPath());
            return;
        } catch (ClassNotFoundException e) {
            // JDK6
        } catch (ExceptionInInitializerError e) {
            // JDK7 on multibyte encoding (http://bugs.java.com/bugdatabase/view_bug.do?bug_id=7050570)
        }

        // Fallback
        if (!file.mkdirs()) {
            if (!file.isDirectory()) {
                throw new IOException("Directory not created");
            }
        }
    }
}
