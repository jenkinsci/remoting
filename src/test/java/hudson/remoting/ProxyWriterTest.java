package hudson.remoting;

import org.jvnet.hudson.test.Bug;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

/**
 * @author Kohsuke Kawaguchi
 */
public class ProxyWriterTest extends RmiTestBase implements Serializable {
    ByteArrayOutputStream log = new ByteArrayOutputStream();
    StreamHandler logRecorder = new StreamHandler(log,new SimpleFormatter());
    Logger logger = Logger.getLogger(Channel.class.getName());

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        logger.addHandler(logRecorder);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        logger.removeHandler(logRecorder);
    }


    @Bug(20769)
    public void testRemoteGC() throws InterruptedException, IOException {
        StringWriter sw = new StringWriter();
        final RemoteWriter w = new RemoteWriter(sw);

        channel.call(new Callable<Void, IOException>() {
            public Void call() throws IOException {
                w.write("hello");
                return null;
            }
        });

        // induce a GC. There's no good reliable way to do this,
        // and if GC doesn't happen within this loop, the test can pass
        // even when the underlying problem exists.
        for (int i=0; i<50; i++) {
            logRecorder.flush();
            assertTrue("There shouldn't be any errors: " + log.toString(), log.size() == 0);

            Thread.sleep(100);
            channel.call(new Callable<Void, IOException>() {
                public Void call() throws IOException {
                    System.gc();
                    return null;
                }
            });
        }
    }

    private Object writeReplace() {
        return null;
    }
}
