package hudson.remoting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
            long l1 = 0, l2 = 0;
            for (int i = 0; i < numOfLong; i++) {
                long l = in.readLong();
                if (i % 2 == 0) {
                    l1 ^= l;
                } else {
                    l2 ^= l;
                }
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
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        long l = sum1 ^ sum2;
        return (int) (l ^ (l >>> 32));
    }

    @Override
    public String toString() {
        return String.format("%016X%016X", sum1, sum2);
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
    @SuppressFBWarnings(
            value = "URLCONNECTION_SSRF_FD",
            justification = "This is only used for managing the jar cache as files, not URLs.")
    static Checksum forURL(URL url) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(JarLoaderImpl.DIGEST_ALGORITHM);
            try (InputStream istream = url.openStream();
                    OutputStream ostream = new DigestOutputStream(OutputStream.nullOutputStream(), md)) {
                Util.copy(istream, ostream);
                return new Checksum(md.digest(), md.getDigestLength() / 8);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
