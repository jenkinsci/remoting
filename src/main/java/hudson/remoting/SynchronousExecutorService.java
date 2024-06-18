package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link ExecutorService} that executes synchronously.
 *
 * @author Kohsuke Kawaguchi
 */
class SynchronousExecutorService extends AbstractExecutorService {
    private volatile boolean shutdown = false;
    private int count = 0;

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    @NonNull
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutdown && count == 0;
    }

    @Override
    public synchronized boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long now = System.nanoTime();
        long end = now + unit.toNanos(timeout);

        while (count != 0) {
            long d = end - now;
            if (d <= 0) {
                return false;
            }
            wait(TimeUnit.NANOSECONDS.toMillis(d));
            now = System.nanoTime();
        }
        return true;
    }

    @Override
    public void execute(@NonNull Runnable command) {
        if (shutdown) {
            throw new IllegalStateException("Already shut down");
        }
        touchCount(1);
        try {
            command.run();
        } finally {
            touchCount(-1);
        }
    }

    private synchronized void touchCount(int diff) {
        count += diff;
        if (count == 0) {
            notifyAll();
        }
    }
}
