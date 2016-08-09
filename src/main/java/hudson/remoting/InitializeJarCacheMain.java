package hudson.remoting;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;

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

    private static final FilenameFilter JAR_FILE_FILTER = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.endsWith(".jar");
        }
    };

    /**
     * Requires 2 parameters:
     * <ol>
     * <li>The source jar directory.
     * <li>The jar cache directory.
     * </ol>
     */
    public static void main(String[] argv) throws Exception {
        if (argv.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: java -cp slave.jar hudson.remoting.InitializeJarCacheMain " +
                    "<source jar dir> <jar cache dir>");
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

            Util.mkdirs(newJarLocation.getParentFile());
            copyFile(jar, newJarLocation);
        }
    }

    /**
     * Util method to copy file from one location to another.
     *
     * <p>We don't have access to Guava, apache or Java7, so we have to write
     * our own from scratch.
     */
    private static void copyFile(File src, File dest) throws Exception {
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            input = new FileInputStream(src);
            output = new FileOutputStream(dest);
            byte[] buf = new byte[1024 * 1024];
            int bytesRead;
            while ((bytesRead = input.read(buf)) > 0) {
                output.write(buf, 0, bytesRead);
            }
        } finally {
            if (input != null) {
                input.close();
            }
            if (output != null) {
                output.close();
            }
        }
    }
}
