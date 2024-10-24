/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Ullrich Hafner
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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

/**
 * Locates where a given class is loaded from.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressFBWarnings(
        value = "PATH_TRAVERSAL_IN",
        justification = "Managed by the jar cache mechanism, using server data.")
public class Which {
    /**
     * Returns the URL of the class file where the given class has been loaded from.
     *
     * @param clazz Class
     * @throws IllegalArgumentException
     *      if failed to determine the URL.
     * @return URL of the class file
     * @since 2.24
     */
    @NonNull
    public static URL classFileUrl(Class<?> clazz) throws IOException {
        ClassLoader cl = clazz.getClassLoader();
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        URL res = cl.getResource(clazz.getName().replace('.', '/') + ".class");
        if (res == null) {
            throw new IllegalArgumentException("Unable to locate class file for " + clazz);
        }
        return res;
    }

    /**
     * @deprecated Use {@link #classFileUrl(Class)}
     */
    @Deprecated
    public static URL jarURL(Class<?> clazz) throws IOException {
        return classFileUrl(clazz);
    }

    /**
     * Locates the jar file that contains the given class.
     *
     * <p>
     * Note that jar files are not always loaded from {@link File},
     * so for diagnostics purposes {@link #jarURL(Class)} is preferrable.
     *
     * @param clazz Class
     * @throws IllegalArgumentException
     *      if failed to determine the class File URL.
     * @return
     *      JAR File, which contains the class.
     */
    @NonNull
    public static File jarFile(Class<?> clazz) throws IOException {
        return jarFile(classFileUrl(clazz), clazz.getName().replace('.', '/') + ".class");
    }

    // TODO: This method will likely start blowing up in Java 9. Needs some testing.
    /**
     * Locates the jar file that contains the given resource
     *
     * @param res
     *      The URL that points to the location of the resource.
     * @param qualifiedName
     *      Fully qualified resource name of the resource being looked up,
     *      such as "pkg/Outer$Inner.class" or "abc/def/msg.properties".
     *      This is normally a part of the {@code res} parameter, but some
     *      VFS makes it necessary to get this information from outside to figure out what the jar file is.
     * @throws IllegalArgumentException
     *      If the URL is not in a jar file.
     * @return
     *      JAR File, which contains the URL
     */
    @NonNull
    @SuppressFBWarnings(
            value = "URLCONNECTION_SSRF_FD",
            justification = "Used by the agent as part of jar cache management.")
    /*package*/ static File jarFile(URL res, String qualifiedName) throws IOException {
        String resURL = res.toExternalForm();
        String originalURL = resURL;
        if (resURL.startsWith("jar:file:") || resURL.startsWith("wsjar:file:")) {
            return fromJarUrlToFile(resURL);
        }

        if (resURL.startsWith("code-source:/")) {
            // OC4J apparently uses this. See http://www.nabble.com/Hudson-on-OC4J-tt16702113.html
            resURL = resURL.substring(
                    "code-source:/".length(), resURL.lastIndexOf('!')); // cut off jar: and the file name portion
            return new File(decode(new URL("file:/" + resURL).getPath()));
        }

        if (resURL.startsWith("zip:")) {
            // weblogic uses this. See http://www.nabble.com/patch-to-get-Hudson-working-on-weblogic-td23997258.html
            // also see http://www.nabble.com/Re%3A-Hudson-on-Weblogic-10.3-td25038378.html#a25043415
            resURL = resURL.substring(
                    "zip:".length(), resURL.lastIndexOf('!')); // cut off zip: and the file name portion
            return new File(decode(new URL("file:" + resURL).getPath()));
        }

        if (resURL.startsWith("file:")) {
            // unpackaged classes
            int n = qualifiedName.split("/").length; // how many slashes do wo need to cut?
            for (; n > 0; n--) {
                int idx = Math.max(resURL.lastIndexOf('/'), resURL.lastIndexOf('\\'));
                if (idx < 0) {
                    throw new IllegalArgumentException(originalURL + " - " + resURL);
                }
                resURL = resURL.substring(0, idx);
            }

            // won't work if res URL contains ' '
            // return new File(new URI(null,new URL(res).toExternalForm(),null));
            // won't work if res URL contains '%20'
            // return new File(new URL(res).toURI());

            return new File(decode(new URL(resURL).getPath()));
        }

        if (resURL.startsWith("vfszip:")) {
            // JBoss5
            try (InputStream is = res.openStream()) {
                Object delegate = is;
                while (delegate.getClass().getEnclosingClass() != ZipFile.class) {
                    Field f = delegate.getClass().getDeclaredField("delegate");
                    f.setAccessible(true);
                    delegate = f.get(delegate);
                    // JENKINS-5922 - workaround for CertificateReaderInputStream; JBoss 5.0.0, EAP 5.0 and EAP 5.1
                    // java.util.jar.JarVerifier is not public in Java, so we have to use reflection
                    if (delegate.getClass().getName().equals("java.util.jar.JarVerifier$VerifierStream")) {
                        f = delegate.getClass().getDeclaredField("is");
                        f.setAccessible(true);
                        delegate = f.get(delegate);
                    }
                }
                Field f = delegate.getClass().getDeclaredField("this$0");
                f.setAccessible(true);
                ZipFile zipFile = (ZipFile) f.get(delegate);
                return new File(zipFile.getName());
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // something must have changed in JBoss5. fall through
                LOGGER.log(Level.FINE, "Failed to resolve vfszip into a jar location", e);
            }
        }

        if (resURL.startsWith("vfs:")) {
            // JBoss6
            String dotdot = "../".repeat(Math.max(0, qualifiedName.split("/").length - 1));

            try {
                URL jar = new URL(res, dotdot);
                String path = jar.getPath();
                if (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                // obtain the file name portion
                String fileName = path.substring(path.lastIndexOf('/') + 1);

                Object vfs = new URL(jar, "..").getContent(); // a VirtualFile object pointing to the parent of the jar
                File dir = (File) vfs.getClass().getMethod("getPhysicalFile").invoke(vfs);

                File jarFile = new File(dir, fileName);
                if (jarFile.exists()) {
                    return jarFile;
                }
            } catch (RuntimeException
                    | NoSuchMethodException
                    | IllegalAccessException
                    | IOException
                    | InvocationTargetException e) {
                LOGGER.log(Level.FINE, "Failed to resolve vfs file into a location", e);
            }
        }

        URLConnection con = res.openConnection();
        if (con instanceof JarURLConnection) {
            JarURLConnection jcon = (JarURLConnection) con;
            JarFile jarFile = jcon.getJarFile();
            if (jarFile != null) {
                String n = jarFile.getName();
                if (n.length() > 0) { // JDK6u10 needs this
                    return new File(n);
                } else {
                    // JDK6u10 apparently starts hiding the real jar file name,
                    // so this just keeps getting tricker and trickier...
                    // TODO: this is a bit insane, but it is not covered by autotests now.
                    // Needs to be solved by Remoting test harness if there is a plan to have such fallback for Java 9
                    try {
                        Field f = ZipFile.class.getDeclaredField("name");
                        f.setAccessible(true);
                        return new File((String) f.get(jarFile));
                    } catch (NoSuchFieldException | IllegalAccessException e) {
                        LOGGER.log(Level.INFO, "Failed to obtain the local cache file name of " + resURL, e);
                    }
                }
            }
        }

        throw new IllegalArgumentException(originalURL + " - " + resURL);
    }

    public static File jarFile(URL resource) throws IOException {
        return fromJarUrlToFile(resource.toExternalForm());
    }

    private static File fromJarUrlToFile(String resURL) throws MalformedURLException {
        resURL = resURL.substring(
                resURL.indexOf(':') + 1, resURL.lastIndexOf('!')); // cut off "scheme:" and the file name portion
        return new File(decode(new URL(resURL).getPath()));
    }

    /**
     * Decode '%HH'.
     */
    private static String decode(String s) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '%') {
                baos.write(hexToInt(s.charAt(i + 1)) * 16 + hexToInt(s.charAt(i + 2)));
                i += 2;
                continue;
            }
            baos.write(ch);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static int hexToInt(int ch) {
        return Character.getNumericValue(ch);
    }

    private static final Logger LOGGER = Logger.getLogger(Which.class.getName());
}
