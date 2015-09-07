package hudson.remoting;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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
 * Represents 128bit checksum of a jar file.
 *
 * @author Kohsuke Kawaguchi
 */
final class Checksum {
    public final long sum1, sum2;

    Checksum(long sum1, long sum2) {
        this.sum1 = sum1;
        this.sum2 = sum2;
    }

    private Checksum(byte[] arrayOf16bytes, int numOfLong) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(arrayOf16bytes));
            long l1=0,l2=0;
            for (int i=0; i<numOfLong; i++) {
                long l = in.readLong();
                if (i%2==0)
                    l1 ^= l;
                else
                    l2 ^= l;
            }
            sum1 = l1;
            sum2 = l2;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Checksum) {
            Checksum that = (Checksum) o;
            return sum1 == that.sum1 && sum2 == that.sum2;
        } else
            return false;
    }

    @Override
    public int hashCode() {
        long l = sum1 ^ sum2;
        return (int) (l ^ (l >>> 32));
    }

    @Override
    public String toString() {
        return String.format("%016X%016X",sum1,sum2);
    }

    /**
     * Returns the checksum for the given file.
     */
    static Checksum forFile(File file) throws IOException {
        return forURL(file.toURI().toURL());
    }

    /**
     * Returns the checksum for the given URL.
     */
    static Checksum forURL(URL url) throws IOException {
        if (CHECKSUMS_BY_URL.containsKey(url)) {
            return CHECKSUMS_BY_URL.get(url);
        }

        return calculateFor(url);
    }

    /**
     * Allow calculating checksums only one at a time.
     *
     * <p>This method caches calculated checksums so future calls to
     * {@link #forURL)} should not need to re-calculate the value.
     *
     * <p>Even if many slaves connect at around the same time, the checksums
     * should only be calculated once. Making this method synchronized ensures
     * this behavior.
     *
     * <p>Previously when a large number of slaves connected at the same time
     * the master would experience a spike in CPU and probably I/O. By caching
     * the results and synchronizing the calculation of the results this issue
     * is addressed.
     */
    private synchronized static Checksum calculateFor(URL url) throws IOException {
        // When callers all request the checksum of a large jar the calls to
        // forURL will all fall through to this method since the first caller's
        // calculation may take a while. Hence re-check the cache at the start.
        if (CHECKSUMS_BY_URL.containsKey(url)) {
            return CHECKSUMS_BY_URL.get(url);
        }

        try {
            MessageDigest md = MessageDigest.getInstance(JarLoaderImpl.DIGEST_ALGORITHM);
            Util.copy(url.openStream(), new DigestOutputStream(new NullOutputStream(), md));
            Checksum checksum =  new Checksum(md.digest(), md.getDigestLength() / 8);
            CHECKSUMS_BY_URL.putIfAbsent(url, checksum);
            return checksum;
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static class NullOutputStream extends OutputStream {
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

    @edu.umd.cs.findbugs.annotations.SuppressWarnings("DMI_COLLECTION_OF_URLS")
    private static final ConcurrentMap<URL,Checksum> CHECKSUMS_BY_URL =
        new ConcurrentHashMap<URL,Checksum>();
}
