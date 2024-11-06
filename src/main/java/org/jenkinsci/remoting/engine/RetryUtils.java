package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.EngineListenerSplitter;
import hudson.remoting.Util;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.util.DurationFormatter;

class RetryUtils {
    private static final Logger LOGGER = Logger.getLogger(RetryUtils.class.getName());

    @CheckForNull
    static <T> T succeedsWithRetries(
            @NonNull Callable<T> supplier, @NonNull Duration noReconnectAfter, @NonNull EngineListenerSplitter events)
            throws InterruptedException {
        return succeedsWithRetries(supplier, noReconnectAfter, events, x -> "Failed to connect: " + x.getMessage());
    }
    /**
     * Evaluates a supplier with exponential backoff until it provides a non-null value or the timeout is reached.
     * @param supplier supplies an object. If null, retries with exponential backoff will be attempted.
     * @return true if the condition succeeded, false if the condition failed and the timeout was reached
     * @throws InterruptedException if the thread was interrupted while waiting.
     */
    @CheckForNull
    static <T> T succeedsWithRetries(
            @NonNull Callable<T> supplier,
            @NonNull Duration noReconnectAfter,
            @NonNull EngineListenerSplitter events,
            @NonNull Function<Exception, String> exceptionConsumer)
            throws InterruptedException {
        for (var exponentialRetry = new ExponentialRetry(noReconnectAfter);
                exponentialRetry != null;
                exponentialRetry = exponentialRetry.next(events)) {
            try {
                var result = supplier.call();
                if (result != null) {
                    return result;
                }
            } catch (Exception x) {
                var msg = exceptionConsumer.apply(x);
                events.status(msg);
                LOGGER.log(Level.FINE, msg, x);
            }
        }
        return null;
    }

    private record ExponentialRetry(
            int factor,
            Instant beginning,
            Duration delay,
            Duration timeout,
            Duration incrementDelay,
            Duration maxDelay) {

        ExponentialRetry(Duration timeout) {
            this(2, Instant.now(), Duration.ofSeconds(0), timeout, Duration.ofSeconds(1), Duration.ofSeconds(10));
        }

        private static Duration min(Duration a, Duration b) {
            return a.compareTo(b) < 0 ? a : b;
        }

        boolean timeoutExceeded() {
            return Util.shouldBailOut(beginning, timeout);
        }

        ExponentialRetry next(EngineListenerSplitter events) throws InterruptedException {
            var next = new ExponentialRetry(factor, beginning, nextDelay(), timeout, incrementDelay, maxDelay);
            if (next.timeoutExceeded()) {
                events.status("Bailing out after " + DurationFormatter.format(next.timeout));
                return null;
            } else {
                events.status("Waiting " + DurationFormatter.format(next.delay) + " before retry");
                Thread.sleep(next.delay.toMillis());
            }
            return next;
        }

        private Duration nextDelay() {
            return min(maxDelay, delay.multipliedBy(factor).plus(incrementDelay));
        }
    }
}
