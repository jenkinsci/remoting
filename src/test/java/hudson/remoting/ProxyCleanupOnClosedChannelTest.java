package hudson.remoting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.jenkinsci.remoting.ChannelStateException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
    private final List<Level> previousLevels = new ArrayList<>();

    /** The compatibility runner negotiates no capabilities, which sends {@code ProxyWriter} down its {@code EOF} branch. */
    static Stream<ChannelRunner> runners() {
        return Stream.of(new InProcessRunner(), new InProcessCompatibilityRunner());
    }

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

    @ParameterizedTest
    @MethodSource("runners")
    void proxyOutputStreamCollectedAfterTheChannelClosedDoesNotWarn(ChannelRunner runner) throws Exception {
        Channel channel = runner.start();
        WeakReference<ProxyOutputStream> stream = new WeakReference<>(new ProxyOutputStream(channel, 1));
        runner.stop(channel);

        collect(stream);

        assertCleanupLoggedAtFine(channel);
    }

    @ParameterizedTest
    @MethodSource("runners")
    void proxyWriterCollectedAfterTheChannelClosedDoesNotWarn(ChannelRunner runner) throws Exception {
        Channel channel = runner.start();
        WeakReference<ProxyWriter> writer = new WeakReference<>(new ProxyWriter(channel, 1));
        runner.stop(channel);

        collect(writer);

        assertCleanupLoggedAtFine(channel);
    }

    private static void collect(WeakReference<?> ref) throws InterruptedException {
        for (int i = 0; i < 100 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertNull(ref.get(), "the proxy was never collected, so its cleanup cannot have run");
    }

    /**
     * Wait for the cleaner thread, which runs some time after the collection, to log the failed send on this
     * channel, at whatever level, then check the level.
     */
    private void assertCleanupLoggedAtFine(Channel channel) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<LogRecord> record = records.stream()
                    .filter(r -> r.getThrown() instanceof ChannelStateException e && e.getChannel() == channel)
                    .findFirst();
            if (record.isPresent()) {
                assertInstanceOf(ChannelClosedException.class, record.get().getThrown());
                assertEquals(
                        Level.FINE,
                        record.get().getLevel(),
                        "a closed channel is not worth a warning; logged: " + messages());
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the cleanup never reported the closed channel; logged: " + messages());
    }

    private List<String> messages() {
        return records.stream().map(r -> r.getLevel() + " " + r.getMessage()).toList();
    }
}
