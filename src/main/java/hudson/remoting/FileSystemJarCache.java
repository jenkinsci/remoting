package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.jcip.annotations.GuardedBy;
import org.jenkinsci.remoting.util.PathUtils;

/**
 * {@link JarCache} that stores files in a single directory.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.24
 */
public class FileSystemJarCache extends JarCacheSupport {
    public final File rootDir;

    private final boolean touch;

    /**
     * We've reported these checksums as present on this side.
     */
    private final Set<Checksum> notified = Collections.synchronizedSet(new HashSet<>());

    /**
     * Cache of computer checksums for cached jars.
     */
    @GuardedBy("itself")
    private final Map<String, Checksum> checksumsByPath = new HashMap<>();

    // TODO: Create new IOException constructor
    /**
     * @param rootDir
     *      Root directory.
     * @param touch
     *      True to touch the cached jar file that's used. This enables external LRU based cache
     *      eviction at the expense of increased I/O.
     * @throws IllegalArgumentException
     *      Root directory is {@code null} or not writable.
     */
    public FileSystemJarCache(@NonNull File rootDir, boolean touch) {
        this.rootDir = rootDir;
        this.touch = touch;
        if (rootDir == null) {
            throw new IllegalArgumentException("Root directory is null");
        }

        try {
            Files.createDirectories(rootDir.toPath());
        } catch (IOException ex) {
            throw new IllegalArgumentException("Root directory not writable: " + rootDir, ex);
        }
    }

    @Override
    public String toString() {
        return String.format("FileSystem JAR Cache: path=%s, touch=%s", rootDir, touch);
    }

    @Override
    protected URL lookInCache(Channel channel, long sum1, long sum2) throws IOException, InterruptedException {
        File jar = map(sum1, sum2);
        if (jar.exists()) {
            LOGGER.log(Level.FINER, () -> String.format("Jar file cache hit %16X%16X", sum1, sum2));
            if (touch) {
                Files.setLastModifiedTime(PathUtils.fileToPath(jar), FileTime.fromMillis(System.currentTimeMillis()));
            }
            if (notified.add(new Checksum(sum1, sum2))) {
                getJarLoader(channel).notifyJarPresence(sum1, sum2);
            }
            return jar.toURI().toURL();
        }
        return null;
    }

    @Override
    @SuppressFBWarnings(
            value = "PATH_TRAVERSAL_IN",
            justification = "The file path is a generated value based on server supplied data.")
    protected URL retrieve(Channel channel, long sum1, long sum2) throws IOException, InterruptedException {
        Checksum expected = new Checksum(sum1, sum2);
        File target = map(sum1, sum2);

        if (target.exists()) {
            Checksum actual = fileChecksum(target);
            if (expected.equals(actual)) {
                LOGGER.fine(String.format("Jar file already exists: %s", expected));
                return target.toURI().toURL();
            }

            LOGGER.warning(String.format(
                    "Cached file checksum mismatch: %s%nExpected: %s%n Actual: %s",
                    target.getAbsolutePath(), expected, actual));
            Files.delete(PathUtils.fileToPath(target));
            synchronized (checksumsByPath) {
                checksumsByPath.remove(target.getCanonicalPath());
            }
        }

        try {
            File tmp = createTempJar(target);
            try {
                try (RemoteOutputStream o = new RemoteOutputStream(new FileOutputStream(tmp))) {
                    LOGGER.log(Level.FINE, () -> String.format("Retrieving jar file %16X%16X", sum1, sum2));
                    getJarLoader(channel).writeJarTo(sum1, sum2, o);
                }

                // Verify the checksum of the download.
                Checksum actual = Checksum.forFile(tmp);
                if (!expected.equals(actual)) {
                    throw new IOException(String.format(
                            "Incorrect checksum of retrieved jar: %s%nExpected: %s%nActual: %s",
                            tmp.getAbsolutePath(), expected, actual));
                }

                if (!tmp.renameTo(target)) {
                    if (!target.exists()) {
                        throw new IOException("Unable to create " + target + " from " + tmp);
                    }
                    // Even if we fail to rename, we are OK as long as the target actually exists at
                    // this point. This can happen if two FileSystemJarCache instances share the
                    // same cache dir.
                    //
                    // Verify the checksum to be sure the target is correct.
                    actual = fileChecksum(target);
                    if (!expected.equals(actual)) {
                        throw new IOException(String.format(
                                "Incorrect checksum of previous jar: %s%nExpected: %s%nActual: %s",
                                target.getAbsolutePath(), expected, actual));
                    }
                }

                return target.toURI().toURL();
            } finally {
                Files.deleteIfExists(PathUtils.fileToPath(tmp));
            }
        } catch (IOException e) {
            throw new IOException("Failed to write to " + target, e);
        }
    }

    /**
     * Get file checksum calculating it or retrieving from cache.
     */
    private Checksum fileChecksum(File file) throws IOException {
        String location = file.getCanonicalPath();

        // When callers all request the checksum of a large jar, the first thread
        // will calculate the checksum and the other treads will be blocked here
        // until calculated to be picked up from cache right away.
        synchronized (checksumsByPath) {
            Checksum checksum = checksumsByPath.get(location);
            if (checksum != null) {
                return checksum;
            }

            checksum = Checksum.forFile(file);
            checksumsByPath.put(location, checksum);
            return checksum;
        }
    }

    @SuppressFBWarnings(
            value = "PATH_TRAVERSAL_IN",
            justification = "This path exists within a temp directory so the potential traversal is limited.")
    /*package for testing*/ File createTempJar(@NonNull File target) throws IOException {
        File parent = target.getParentFile();
        Files.createDirectories(parent.toPath());
        return File.createTempFile(target.getName(), "tmp", parent);
    }

    /**
     * Map to the cache jar file name.
     */
    File map(long sum1, long sum2) {
        return new File(
                rootDir,
                String.format("%02X/%014X%016X.jar", (int) (sum1 >>> (64 - 8)), sum1 & 0x00FFFFFFFFFFFFFFL, sum2));
    }

    private static final Logger LOGGER = Logger.getLogger(FileSystemJarCache.class.getName());
}
