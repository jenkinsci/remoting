package hudson.remoting;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Enumeration;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.jenkinsci.remoting.util.PathUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

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
        return path.substring(path.lastIndexOf('/') + 1);
    }

    static byte[] readFully(InputStream in) throws IOException {
        // TODO perhaps replace by in.readAllBytes() after checking close behavior
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        copy(in, baos);
        return baos.toByteArray();
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        try (in) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    @NonNull
    @SuppressFBWarnings(
            value = "PATH_TRAVERSAL_IN",
            justification = "This path exists within a temp directory so the potential traversal is limited.")
    static File makeResource(String name, byte[] image) throws IOException {
        Path tmpDir = Files.createTempDirectory("resource-");
        File resource = new File(tmpDir.toFile(), name);
        Files.createDirectories(PathUtils.fileToPath(resource.getParentFile()));
        Files.createFile(PathUtils.fileToPath(resource));

        try (FileOutputStream fos = new FileOutputStream(resource)) {
            fos.write(image);
        }

        deleteDirectoryOnExit(tmpDir.toFile());
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
                for (File f : childFiles) {
                    deleteDirectoryOnExit(f);
                }
            }
        }
    }

    static String indent(String s) {
        return "    " + s.trim().replace("\n", "\n    ");
    }

    public static String getVersion() {
        String version = "unknown";
        try {
            Enumeration<URL> resEnum =
                    Thread.currentThread().getContextClassLoader().getResources(JarFile.MANIFEST_NAME);
            while (resEnum.hasMoreElements()) {
                URL url = resEnum.nextElement();
                try (InputStream is = url.openStream()) {
                    if (is != null) {
                        Manifest manifest = new Manifest(is);
                        version = manifest.getMainAttributes().getValue("Version");
                        if (version != null) {
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

    public static boolean shouldBailOut(@NonNull Instant firstAttempt, @CheckForNull Duration noReconnectAfter) {
        if (noReconnectAfter == null) {
            return false;
        }
        return Duration.between(firstAttempt, Instant.now()).compareTo(noReconnectAfter) > 0;
    }

    private Util() {}
}
