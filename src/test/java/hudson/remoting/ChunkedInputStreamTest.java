package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.pipe.RandomWorkload;
import hudson.remoting.pipe.Workload;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jenkinsci.remoting.nio.FifoBuffer;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author Kohsuke Kawaguchi
 */
public class ChunkedInputStreamTest extends Assert {

    FifoBuffer buf = new FifoBuffer(53, 1024 * 1024);
    ChunkedInputStream i = new ChunkedInputStream(buf.getInputStream());
    ChunkedOutputStream o = new ChunkedOutputStream(37, buf.getOutputStream());

    ExecutorService es = Executors.newFixedThreadPool(2);

    @After
    public void tearDown() {
        es.shutdown();
    }

    /**
     * Just copy 10MB of random data.
     */
    @Test
    public void tenMegaCopy() throws Exception {
        test(new RandomWorkload(10 * 1024 * 1024), i, new AutoChunkedOutputStream(o));
    }

    @Test
    public void boundaryPositionCheck() throws Exception {
        test(
                new Workload() {
                    int size = 1024;

                    @Override
                    public void write(OutputStream o) throws IOException {
                        Random boundary = new Random(0);
                        Random data = new Random(1);

                        for (int j = 0; j < size; j++) {
                            byte[] buf = new byte[boundary.nextInt(4096)];
                            data.nextBytes(buf);
                            o.write(buf);
                            ((ChunkedOutputStream) o).sendBreak();
                        }

                        o.close();
                    }

                    @Override
                    public void read(InputStream i) throws IOException {
                        Random boundary = new Random(0);
                        Random data = new Random(1);

                        for (int j = 0; j < size; j++) {
                            byte[] buf = new byte[boundary.nextInt(4096)];
                            data.nextBytes(buf);

                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            ((ChunkedInputStream) i).readUntilBreak(baos);

                            assertEquals(buf.length, baos.size());
                            assertArrayEquals(buf, baos.toByteArray());
                        }

                        assertEquals(-1, i.read());

                        i.close();
                    }
                },
                i,
                o);
    }

    private void test(final Workload w, final InputStream i, final OutputStream o) throws Exception {
        Future<Object> fw = es.submit(() -> {
            w.write(o);
            return null;
        });
        Future<Object> fr = es.submit(() -> {
            w.read(i);
            return null;
        });

        fr.get();
        fw.get();
    }

    static class AutoChunkedOutputStream extends FilterOutputStream {
        AutoChunkedOutputStream(ChunkedOutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(@NonNull byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            ((ChunkedOutputStream) out).sendBreak();
        }
    }
}
