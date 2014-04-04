package hudson.remoting;

import hudson.remoting.pipe.RandomWorkload;
import hudson.remoting.pipe.Workload;
import org.jenkinsci.remoting.nio.FifoBuffer;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * @author Kohsuke Kawaguchi
 */
public class ChunkedInputStreamTest {

    FifoBuffer buf = new FifoBuffer(115,1024*1024);
    ChunkedInputStream i = new ChunkedInputStream(buf.getInputStream());
    ChunkedOutputStream o = new ChunkedOutputStream(137,buf.getOutputStream());

    ExecutorService es = Executors.newFixedThreadPool(2);

    @After
    public void tearDown() {
        es.shutdown();
    }

    @Test
    public void tenMegaCopy() throws Exception {
        test(new RandomWorkload(10*1024*1024));
    }

    private void test(final Workload w) throws Exception {
        Future<Object> fw = es.submit(new Callable<Object>() {
            public Object call() throws Exception {
                w.write(o);
                return null;
            }
        });
        Future<Object> fr = es.submit(new Callable<Object>() {
            public Object call() throws Exception {
                w.read(i);
                return null;
            }
        });

        fr.get();
        fw.get();
    }
}
