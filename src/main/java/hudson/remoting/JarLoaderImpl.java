package hudson.remoting;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Implements {@link JarLoader} to be called from the other side.
 *
 * <p>
 * This implementation can be safely shared across multiple {@link Channel}s
 * to improve the performance.
 *
 * @author Kohsuke Kawaguchi
 */
class JarLoaderImpl implements JarLoader {
    private final ConcurrentMap<Checksum,URL> knownJars = new ConcurrentHashMap<Checksum,URL>();
    private final ConcurrentMap<URL,Checksum> checksums = new ConcurrentHashMap<URL,Checksum>();

    public void writeJarTo(long sum1, long sum2, OutputStream sink) throws IOException, InterruptedException {
        Checksum k = new Checksum(sum1, sum2);
        URL url = knownJars.get(k);
        if (url==null)
            throw new IOException("Unadvertised jar file "+k);

        Util.copy(url.openStream(), sink);
    }

    public Checksum calcChecksum(File jar) throws IOException {
        return calcChecksum(jar.toURI().toURL());
    }

    /**
     * Obtains the checksum for the jar at the specified URL.
     */
    public Checksum calcChecksum(URL jar) throws IOException {
        Checksum v = checksums.get(jar);    // cache hit
        if (v!=null)    return v;

        try {
            MessageDigest md = MessageDigest.getInstance(DIGEST_ALGORITHM);
            Util.copy(jar.openStream(),new DigestOutputStream(new NullOutputStream(),md));
            v = new Checksum(md.digest(),md.getDigestLength()/8);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }

        knownJars.put(v,jar);
        checksums.put(jar,v);
        return v;
    }

    class NullOutputStream extends OutputStream {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }

    public static final String DIGEST_ALGORITHM = System.getProperty(JarLoaderImpl.class.getName()+".algorithm","SHA-256");
}
