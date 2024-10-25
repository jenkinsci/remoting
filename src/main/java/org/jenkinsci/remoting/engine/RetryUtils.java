package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.EngineListenerSplitter;
import hudson.remoting.Util;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
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
        var exponentialRetry = new ExponentialRetry(noReconnectAfter);
        while (exponentialRetry != null) {
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
            exponentialRetry = exponentialRetry.next(events);
        }
        return null;
    }

    private static class ExponentialRetry {
        final int factor;
        final Instant beginning;
        final Duration delay;
        final Duration timeout;
        final Duration incrementDelay;
        final Duration maxDelay;

        ExponentialRetry(Duration timeout) {
            this(Duration.ofSeconds(0), timeout, 2, Duration.ofSeconds(1), Duration.ofSeconds(10));
        }

        ExponentialRetry(
                Duration initialDelay, Duration timeout, int factor, Duration incrementDelay, Duration maxDelay) {
            this.beginning = Instant.now();
            this.delay = initialDelay;
            this.timeout = timeout;
            this.factor = factor;
            this.incrementDelay = incrementDelay;
            this.maxDelay = maxDelay;
        }

        ExponentialRetry(ExponentialRetry previous) {
            beginning = previous.beginning;
            factor = previous.factor;
            timeout = previous.timeout;
            incrementDelay = previous.incrementDelay;
            maxDelay = previous.maxDelay;
            delay = min(maxDelay, previous.delay.multipliedBy(previous.factor).plus(incrementDelay));
        }

        private static Duration min(Duration a, Duration b) {
            return a.compareTo(b) < 0 ? a : b;
        }

        boolean timeoutExceeded() {
            return Util.shouldBailOut(beginning, timeout);
        }

        ExponentialRetry next(EngineListenerSplitter events) throws InterruptedException {
            var next = new ExponentialRetry(this);
            if (next.timeoutExceeded()) {
                events.status("Bailing out after " + DurationFormatter.format(next.timeout));
                return null;
            } else {
                events.status("Waiting " + DurationFormatter.format(next.delay) + " before retry");
                TimeUnit.SECONDS.sleep(next.delay.toSeconds());
            }
            return next;
        }
    }
}
