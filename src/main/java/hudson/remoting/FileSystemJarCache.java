package hudson.remoting;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    private final Set<Checksum> notified = Collections.synchronizedSet(new HashSet<Checksum>());

    /**
     * @param touch
     *      True to touch the cached jar file that's used. This enables external LRU based cache
     *      eviction at the expense of increased I/O.
     */
    public FileSystemJarCache(File rootDir, boolean touch) {
        this.rootDir = rootDir;
        this.touch = touch;
        if (rootDir==null)
            throw new IllegalArgumentException();

        try {
            Util.mkdirs(rootDir);
        } catch (IOException ex) {
            throw new RuntimeException("Root directory not writable");
        }
    }

    @Override
    protected URL lookInCache(Channel channel, long sum1, long sum2) throws IOException, InterruptedException {
        File jar = map(sum1, sum2);
        if (jar.exists()) {
            LOGGER.log(Level.FINER, String.format("Jar file cache hit %16X%16X",sum1,sum2));
            if (touch)  jar.setLastModified(System.currentTimeMillis());
            if (notified.add(new Checksum(sum1,sum2)))
                getJarLoader(channel).notifyJarPresence(sum1,sum2);
            return jar.toURI().toURL();
        }
        return null;
    }

    @Override
    protected URL retrieve(Channel channel, long sum1, long sum2) throws IOException, InterruptedException {
        File target = map(sum1, sum2);

        if (target.exists()) {
            // Assume its already been fetched correctly before. ie. We are not going to validate
            // the checksum.
            LOGGER.fine(String.format("Jar file already exists: %16X%16X", sum1, sum2));
            return target.toURI().toURL();
        }

        try {
            File tmp = createTempJar(target);
            try {
                RemoteOutputStream o = new RemoteOutputStream(new FileOutputStream(tmp));
                try {
                    LOGGER.log(Level.FINE, String.format("Retrieving jar file %16X%16X",sum1,sum2));
                    getJarLoader(channel).writeJarTo(sum1, sum2, o);
                } finally {
                    o.close();
                }

                // Verify the checksum of the download.
                Checksum expected = new Checksum(sum1, sum2);
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
                    actual = Checksum.forFile(target);
                    if (!expected.equals(actual)) {
                        throw new IOException(String.format(
                                "Incorrect checksum of previous jar: %s%nExpected: %s%nActual: %s",
                                target.getAbsolutePath(), expected, actual));
                    }
                }


                return target.toURI().toURL();
            } finally {
                tmp.delete();
            }
        } catch (IOException e) {
            throw (IOException)new IOException("Failed to write to "+target).initCause(e);
        }
    }

    /*package for testing*/ File createTempJar(@Nonnull File target) throws IOException {
        File parent = target.getParentFile();
        Util.mkdirs(parent);
        return File.createTempFile(target.getName(), "tmp", parent);
    }

    /**
     * Map to the cache jar file name.
     */
    File map(long sum1, long sum2) {
        return new File(rootDir,String.format("%02X/%014X%016X.jar",
                (int)(sum1>>>(64-8)),
                sum1&0x00FFFFFFFFFFFFFFL, sum2));
    }

    private static final Logger LOGGER = Logger.getLogger(FileSystemJarCache.class.getName());
}
