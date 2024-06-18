package hudson.remoting.pipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;
import org.junit.Assert;

/**
 * Use {@link Random} with the fixed seed as the data stream to detect corruption.
 *
 * To shake things up a bit, reading and writing at the different byte[] size boundary.
 *
 * @author Kohsuke Kawaguchi
 */
public class RandomWorkload extends Assert implements Workload {
    private final long size;

    /**
     * Size of the test.
     */
    public RandomWorkload(long size) {
        this.size = size;
    }

    @Override
    public void write(OutputStream o) throws IOException {
        Random data = new Random(0);
        Random boundary = new Random(1);

        for (long l = 0; l < size; ) {
            int c = boundary.nextInt(4096);
            c = (int) Math.min(c, size - l);

            byte[] buf = new byte[c];
            for (int i = 0; i < c; i++) {
                buf[i] = (byte) data.nextInt();
            }

            o.write(buf);
            l += c;
        }

        o.close();
    }

    @Override
    public void read(InputStream i) throws IOException {
        Random data = new Random(0);
        Random boundary = new Random(2);

        long total = 0;
        while (true) {
            int c = boundary.nextInt(4096);
            byte[] buf = new byte[c];

            int n = i.read(buf);
            if (n < 0) {
                break;
            }

            for (int j = 0; j < n; j++) {
                assertEquals(buf[j], (byte) data.nextInt());
            }

            total += n;
        }

        i.close();
        assertEquals(size, total);
    }
}
