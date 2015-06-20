package hudson.remoting;

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

    static File makeResource(String name, byte[] image) throws IOException {
        File tmpFile = createTempDir();
        File resource = new File(tmpFile, name);
        resource.getParentFile().mkdirs();

        FileOutputStream fos = new FileOutputStream(resource);
        fos.write(image);
        fos.close();

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
     * Gets URL connection.
     * If http_proxy environment variable exists,  the connection uses the proxy.
     */
    static URLConnection openURLConnection(URL url) throws IOException {
        String httpProxy = null;
        // If http.proxyHost property exists, openConnection() uses it.
        if (System.getProperty("http.proxyHost") == null) {
            httpProxy = System.getenv("http_proxy");
        }
        URLConnection con = null;
        if (httpProxy != null && "http".equals(url.getProtocol())) {
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
        return con;
    }

    static InetSocketAddress getResolvedHttpProxyAddress(String host, int port) throws IOException {
        InetSocketAddress targetAddress = null;
        Iterator<Proxy> proxies = ProxySelector.getDefault().select(URI.create(String.format("http://%s:%d", host, port))).iterator();
        while (targetAddress == null && proxies.hasNext()) {
            Proxy proxy = proxies.next();
            if(proxy.type() == Proxy.Type.HTTP) {
                final SocketAddress address = proxy.address();
                if (!(address instanceof InetSocketAddress)) {
                    System.err.println("Unsupported proxy address type " + (address != null ? address.getClass() : "null"));
                    continue;
                }
                InetSocketAddress proxyAddress = (InetSocketAddress) address;
                if(proxyAddress.isUnresolved())
                    proxyAddress = new InetSocketAddress(proxyAddress.getHostName(), proxyAddress.getPort());
                targetAddress = proxyAddress;
            }
        }
        if(targetAddress == null) {
            String httpProxy = System.getenv("http_proxy");
            if(httpProxy != null) {
                try {
                    URL url = new URL(httpProxy);
                    targetAddress = new InetSocketAddress(url.getHost(), url.getPort());
                } catch (MalformedURLException e) {
                    System.err.println("Not use http_proxy environment variable which is invalid: "+e.getMessage());
                }
            }
        }
        return targetAddress;
    }
}
