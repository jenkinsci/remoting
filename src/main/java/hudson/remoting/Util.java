package hudson.remoting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jenkinsci.remoting.util.PathUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Misc. I/O utilities
 *
 * @author Kohsuke Kawaguchi
 */
@Restricted(NoExternalUse.class)
public class Util {
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
    @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "This path exists within a temp directory so the potential traversal is limited.")
    static File makeResource(String name, byte[] image) throws IOException {
        Path tmpDir = Files.createTempDirectory("resource-");
        File resource = new File(tmpDir.toFile(), name);
        Files.createDirectories(PathUtils.fileToPath(resource.getParentFile()));
        Files.createFile(PathUtils.fileToPath(resource));

        try(FileOutputStream fos = new FileOutputStream(resource)) {
            fos.write(image);
        }

        deleteDirectoryOnExit(resource);
        return resource;
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
     * Gets URL connection.
     * If http_proxy environment variable exists,  the connection uses the proxy.
     * Credentials can be passed e.g. to support running Jenkins behind a (reverse) proxy requiring authorization
     */
    @SuppressFBWarnings(value = "URLCONNECTION_SSRF_FD", justification = "Used for retrieving the connection info from the server. We should cleanup the other, unused references.")
    static URLConnection openURLConnection(URL url, String credentials, String proxyCredentials, SSLSocketFactory sslSocketFactory) throws IOException {
        String httpProxy = null;
        // If http.proxyHost property exists, openConnection() uses it.
        if (System.getProperty("http.proxyHost") == null) {
            httpProxy = System.getenv("http_proxy");
        }
        URLConnection con = null;
        if (httpProxy != null && "http".equals(url.getProtocol()) && NoProxyEvaluator.shouldProxy(url.getHost())) {
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
            String encoding = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            con.setRequestProperty("Authorization", "Basic " + encoding);
        }
        if (proxyCredentials != null) {
            String encoding = Base64.getEncoder().encodeToString(proxyCredentials.getBytes(StandardCharsets.UTF_8));
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

    /**
     * @deprecated Use {@link Files#createDirectories(java.nio.file.Path, java.nio.file.attribute.FileAttribute...)} instead.
     */
    @Deprecated
    static void mkdirs(@Nonnull File file) throws IOException {
        if (file.isDirectory()) return;
        Files.createDirectories(PathUtils.fileToPath(file));
    }

    static public String getVersion() {
        String version = "unknown";
        try {
            Enumeration resEnum = Thread.currentThread().getContextClassLoader().getResources(JarFile.MANIFEST_NAME);
            while (resEnum.hasMoreElements()) {
                URL url = (URL) resEnum.nextElement();
                try(InputStream is = url.openStream()) {
                    if (is != null) {
                        Manifest manifest = new Manifest(is);
                        version = manifest.getMainAttributes().getValue("Version");
                        if(version != null) {
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("Could not access manifest");
        }
        return version;
    }
}
