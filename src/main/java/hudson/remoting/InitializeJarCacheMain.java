package hudson.remoting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Takes a directory of jars and populates them into the given jar cache
 * directory with the correct hash names.
 *
 * <p>Ideally this class should exist outside hudson.remoting but unfortunately
 * it needs access to package-private methods in hudson.remoting.
 *
 * @author Akshay Dayal
 */
public class InitializeJarCacheMain {

    private static final FilenameFilter JAR_FILE_FILTER = (dir, name) -> name.endsWith(".jar");

    /**
     * Requires 2 parameters:
     * <ol>
     * <li>The source jar directory.
     * <li>The jar cache directory.
     * </ol>
     */
    @SuppressFBWarnings(
            value = "PATH_TRAVERSAL_IN",
            justification =
                    "These file values are provided by users with sufficient administrative permissions to run this utility program.")
    public static void main(String[] argv) throws Exception {
        if (argv.length != 2) {
            throw new IllegalArgumentException("Usage: java -cp agent.jar hudson.remoting.InitializeJarCacheMain "
                    + "<source jar dir> <jar cache dir>");
        }

        File sourceJarDir = new File(argv[0]);
        File jarCacheDir = new File(argv[1]);
        FileSystemJarCache jarCache = new FileSystemJarCache(jarCacheDir, false);

        File[] jars = sourceJarDir.listFiles(JAR_FILE_FILTER);
        if (jars == null) {
            throw new IOException("Cannot list JAR files in " + sourceJarDir);
        }
        for (File jar : jars) {
            Checksum checksum = Checksum.forFile(jar);
            File newJarLocation = jarCache.map(checksum.sum1, checksum.sum2);

            Files.createDirectories(newJarLocation.getParentFile().toPath());
            Files.copy(jar.toPath(), newJarLocation.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
