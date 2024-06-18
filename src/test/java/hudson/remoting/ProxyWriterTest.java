package hudson.remoting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.jvnet.hudson.test.Issue;

/**
 * @author Kohsuke Kawaguchi
 */
public class ProxyWriterTest implements Serializable {
    ByteArrayOutputStream log = new ByteArrayOutputStream();
    StreamHandler logRecorder = new StreamHandler(log, new SimpleFormatter()) {
        @Override
        public synchronized void publish(LogRecord record) {
            super.publish(record);
            flush();
        }
    };
    Logger logger = Logger.getLogger(Channel.class.getName());

    @Before
    public void setUp() throws Exception {
        logger.addHandler(logRecorder);
    }

    @After
    public void tearDown() throws Exception {
        logger.removeHandler(logRecorder);
    }

    volatile boolean streamClosed;

    /**
     * Exercise all the calls.
     */
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testAllCalls(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            StringWriter sw = new StringWriter();
            final RemoteWriter w = new RemoteWriter(sw);

            channel.call(new WriteBunchOfDataCallable(w));

            StringWriter correct = new StringWriter();
            writeBunchOfData(correct);

            assertEquals(sw.toString(), correct.toString());
            assertEquals(0, log.size()); // no warning should be reported
        });
    }

    private static void writeBunchOfData(Writer w) throws IOException {
        for (int i = 0; i < 1000; i++) {
            w.write('1');
            w.write("hello".toCharArray());
            w.write("abcdef".toCharArray(), 0, 3);
        }
        w.flush();
        for (int i = 0; i < 1000; i++) {
            w.write("hello");
            w.write("abcdef", 0, 3);
        }
        w.close();
    }

    /**
     * If {@link ProxyWriter} gets garbage collected, it should unexport the entry but shouldn't try to close the stream.
     */
    @Issue("JENKINS-20769")
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testRemoteGC(ChannelRunner channelRunner) throws Exception {
        assumeFalse(
                channelRunner instanceof InProcessCompatibilityRunner,
                "in the legacy mode ProxyWriter will try to close the stream, so can't run this test");
        channelRunner.withChannel(channel -> {
            StringWriter sw = new StringWriter() {
                @Override
                public void close() {
                    streamClosed = true;
                }
            };
            final RemoteWriter w = new RemoteWriter(sw);

            channel.call(new WeakReferenceCallable(w));

            // induce a GC. There's no good reliable way to do this,
            // and if GC doesn't happen within this loop, the test can pass
            // even when the underlying problem exists.
            for (int i = 0; i < 30; i++) {
                assertEquals(0, log.size(), "There shouldn't be any errors: " + log.toString());

                Thread.sleep(100);
                if (channel.call(new GcCallable())) {
                    break;
                }
            }

            channel.syncIO();

            assertFalse(streamClosed);
        });
    }

    static WeakReference<RemoteWriter> W;

    /**
     * Basic test of {@link RemoteWriter}/{@link ProxyWriter} test.
     */
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testWriteAndSync(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            StringWriter sw = new StringWriter();
            final RemoteWriter w = new RemoteWriter(sw);

            for (int i = 0; i < 16; i++) {
                channel.call(new WriterCallable(w));
                w.write("2");
            }

            assertEquals("12121212121212121212121212121212", sw.toString());
        });
    }

    private Object writeReplace() {
        return null;
    }

    private static class WriteBunchOfDataCallable extends CallableBase<Void, IOException> {
        private final RemoteWriter w;

        public WriteBunchOfDataCallable(RemoteWriter w) {
            this.w = w;
        }

        @Override
        public Void call() throws IOException {
            writeBunchOfData(w);
            return null;
        }

        private static final long serialVersionUID = 1L;
    }

    private static class WeakReferenceCallable extends CallableBase<Void, IOException> {
        private final RemoteWriter w;

        public WeakReferenceCallable(RemoteWriter w) {
            this.w = w;
        }

        @Override
        public Void call() throws IOException {
            w.write("hello");
            W = new WeakReference<>(w);
            return null;
        }
    }

    private static class GcCallable extends CallableBase<Boolean, IOException> {
        @Override
        public Boolean call() {
            System.gc();
            return W.get() == null;
        }

        private static final long serialVersionUID = 1L;
    }

    private static class WriterCallable extends CallableBase<Void, IOException> {
        private final RemoteWriter w;

        public WriterCallable(RemoteWriter w) {
            this.w = w;
        }

        @Override
        public Void call() throws IOException {
            w.write("1--", 0, 1);
            return null;
        }

        private static final long serialVersionUID = 1L;
    }

    private static final long serialVersionUID = 1L;
}
