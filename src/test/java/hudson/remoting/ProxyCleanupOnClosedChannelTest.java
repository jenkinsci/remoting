package hudson.remoting;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A proxy stream collected after its channel has closed has nothing left to unexport, and says so at
 * {@code FINE} rather than {@code WARNING}.
 */
class ProxyCleanupOnClosedChannelTest {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
    };
    private final List<Logger> loggers =
            List.of(Logger.getLogger(ProxyOutputStream.class.getName()), Logger.getLogger(ProxyWriter.class.getName()));
    private final List<Level> previousLevels = new CopyOnWriteArrayList<>();

    @BeforeEach
    void captureLogs() {
        for (Logger logger : loggers) {
            previousLevels.add(logger.getLevel());
            logger.setLevel(Level.FINE);
            logger.addHandler(handler);
        }
    }

    @AfterEach
    void restoreLogs() {
        for (int i = 0; i < loggers.size(); i++) {
            loggers.get(i).removeHandler(handler);
            loggers.get(i).setLevel(previousLevels.get(i));
        }
    }

    @Test
    void proxyOutputStreamCollectedAfterTheChannelClosedDoesNotWarn() throws Exception {
        InProcessRunner runner = new InProcessRunner();
        Channel channel = runner.start();
        WeakReference<ProxyOutputStream> stream = new WeakReference<>(new ProxyOutputStream(channel, 1));
        runner.stop(channel);

        collect(stream);

        awaitRecord(r -> r.getMessage().contains("not unexporting ProxyOutputStream"));
        assertNoWarning("Failed to unexport ProxyOutputStream");
    }

    @Test
    void proxyWriterCollectedAfterTheChannelClosedDoesNotWarn() throws Exception {
        InProcessRunner runner = new InProcessRunner();
        Channel channel = runner.start();
        WeakReference<ProxyWriter> writer = new WeakReference<>(new ProxyWriter(channel, 1));
        runner.stop(channel);

        collect(writer);

        awaitRecord(r -> r.getMessage().contains("not cleaning up ProxyWriter"));
        assertNoWarning("Failed to cleanup ProxyWriter");
    }

    private static void collect(WeakReference<?> ref) throws InterruptedException {
        for (int i = 0; i < 100 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertTrue(ref.get() == null, "the proxy was never collected, so its cleanup cannot have run");
    }

    /** Wait for the cleaner thread, which runs some time after the collection. */
    private void awaitRecord(Predicate<LogRecord> wanted) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (records.stream().anyMatch(wanted)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the cleanup never reported the closed channel; logged: " + messages());
    }

    private void assertNoWarning(String message) {
        assertTrue(
                records.stream()
                        .noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()
                                && r.getMessage().contains(message)),
                "a closed channel is not worth a warning; logged: " + messages());
    }

    private List<String> messages() {
        return records.stream().map(r -> r.getLevel() + " " + r.getMessage()).toList();
    }
}
