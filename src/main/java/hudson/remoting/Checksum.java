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

    Checksum(byte[] arrayOf16bytes, int numOfLong) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(arrayOf16bytes));
            long l1=0,l2=0;
            for (int i=0; i<numOfLong; i++) {
                long l = in.readLong();
                if (i%2==0)
                    l1 ^= l;
                else
                    l2 ^= in.readLong();
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
}
