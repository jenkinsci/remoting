package hudson.remoting;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

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

    Checksum(byte[] arrayOf16bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(arrayOf16bytes));
            this.sum1 = in.readLong();
            this.sum2 = in.readLong();
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
}
