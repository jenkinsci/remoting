package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.util.ExecutorServiceUtils;

/**
 * Creates an {@link ExecutorService} that executes submitted tasks sequentially
 * on top of another generic arbitrary {@link ExecutorService}.
 *
 * <p>
 * In general, {@link ExecutorService} do not place constraints about the order in which submitted
 * tasks are executed. This class takes such an executor service, then creates a stronger guarantee
 * on the order of the executions. Namely, the submitted tasks are executed in the FIFO order,
 * and no two tasks are executed concurrently.
 *
 * <p>
 * A large number of {@link SingleLaneExecutorService}s backed by a single cached executor service
 * is more efficient than the same number of single-threaded {@link ExecutorService} because of
 * the thread sharing.
 *
 * <p>
 * This class is named {@link SingleLaneExecutorService} because it's akin to create a driving lane
 * in a high way. You can have many lanes, but each lane is strictly sequentially ordered.
 *
 * @author Kohsuke Kawaguchi
 */
public class SingleLaneExecutorService extends AbstractExecutorService {
    private final ExecutorService base;

    private final Queue<Runnable> tasks = new LinkedBlockingQueue<>();
    private boolean scheduled;
    /**
     * We are being shut down. No further submissions are allowed but existing tasks can continue.
     */
    private boolean shuttingDown;
    /**
     * We have finished shut down. Every tasks are full executed.
     */
    private boolean shutDown;

    private static final Logger LOGGER = Logger.getLogger(SingleLaneExecutorService.class.getName());

    /**
     * @param base
     *      Executor service that actually provides the threads that execute tasks.
     */
    public SingleLaneExecutorService(ExecutorService base) {
        this.base = base;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Note that this does not shutdown the wrapped {@link ExecutorService}.
     */
    @Override
    public synchronized void shutdown() {
        shuttingDown = true;
        if (tasks.isEmpty()) {
            shutDown = true;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Note that this does not shutdown the wrapped {@link ExecutorService}.
     */
    @Override
    @NonNull
    public synchronized List<Runnable> shutdownNow() {
        shuttingDown = shutDown = true;
        List<Runnable> all = new LinkedList<>(tasks);
        tasks.clear();
        return all;
    }

    @Override
    public synchronized boolean isShutdown() {
        return shuttingDown;
    }

    @Override
    public synchronized boolean isTerminated() {
        return shutDown;
    }

    @Override
    public synchronized boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long now = System.nanoTime();
        long end = now + unit.toNanos(timeout);
        while (!isTerminated() && (end - now) > 0L) {
            wait(TimeUnit.NANOSECONDS.toMillis(end - now));
            now = System.nanoTime();
        }
        return isTerminated();
    }

    // TODO: create a new method with non-Runtime exceptions and timeout support
    @Override
    public synchronized void execute(@NonNull Runnable command) {
        if (shuttingDown) {
            throw new ExecutorServiceUtils.FatalRejectedExecutionException(
                    "Cannot execute the command " + command + ". The executor service is shutting down");
        }

        this.tasks.add(command);

        // If we haven't been scheduled yet, do so now
        if (!scheduled) {
            scheduled = true;
            try {
                // Submit task in the async mode
                ExecutorServiceUtils.submitAsync(base, runner);
            } catch (ExecutorServiceUtils.ExecutionRejectedException ex) {
                // Wrap by the runtime exception since there is no other solution here
                throw new RejectedExecutionException(
                        "Base executor service " + base + " has rejected the task " + command, ex);
            }
        }
    }

    private final Runnable runner = new Runnable() {
        @Override
        public void run() {
            try {
                tasks.peek().run();
            } finally {
                synchronized (SingleLaneExecutorService.this) {
                    tasks.remove(); // completed. this is needed because shutdown() looks at tasks.isEmpty()

                    assert scheduled;
                    if (!tasks.isEmpty()) {
                        // we have still more things to do
                        try {
                            ExecutorServiceUtils.submitAsync(base, this);
                        } catch (ExecutorServiceUtils.ExecutionRejectedException ex) {
                            // It is supposed to be a fatal error, but we cannot propagate it properly
                            // So the code just logs the error and then throws RuntimeException as it
                            // used to do before the code migration to ExecutorServiceUtils.
                            // TODO: so this behavior still implies the BOOM risk, but there wil be a log entry at least
                            LOGGER.log(
                                    Level.SEVERE,
                                    String.format(
                                            "Base executor service %s has rejected the queue task %s. Propagating the RuntimeException to the caller.",
                                            ex.getExecutorServiceDisplayName(), ex.getRunnableDisplayName()),
                                    ex);
                            throw ExecutorServiceUtils.createRuntimeException(
                                    "Base executor service has rejected the task from the queue", ex);
                        }
                    } else {
                        scheduled = false;
                        if (shuttingDown) {
                            shutDown = true;
                            SingleLaneExecutorService.this.notifyAll();
                        }
                    }
                }
            }
        }
    };
}
