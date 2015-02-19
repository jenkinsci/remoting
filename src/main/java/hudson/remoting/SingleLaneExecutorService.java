package hudson.remoting;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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

    private final Queue<Runnable> tasks = new LinkedBlockingQueue<Runnable>();
    private boolean scheduled;
    /**
     * We are being shut down. No further submissions are allowed but existing tasks can continue.
     */
    private boolean shuttingDown;
    /**
     * We have finished shut down. Every tasks are full executed.
     */
    private boolean shutDown;

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
    public synchronized void shutdown() {
        shuttingDown = true;
        if (tasks.isEmpty())
            shutDown = true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Note that this does not shutdown the wrapped {@link ExecutorService}.
     */
    public synchronized List<Runnable> shutdownNow() {
        shuttingDown = shutDown = true;
        List<Runnable> all = new LinkedList<Runnable>(tasks);
        tasks.clear();
        return all;
    }

    public synchronized boolean isShutdown() {
        return shuttingDown;
    }

    public synchronized boolean isTerminated() {
        return shutDown;
    }

    public synchronized boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long now = System.nanoTime();
        long end = now + unit.toNanos(timeout);
        while (!isTerminated() && (end - now) > 0L) {
            wait(TimeUnit.NANOSECONDS.toMillis(end - now));
            now = System.nanoTime();
        }
        return isTerminated();
    }

    public synchronized void execute(Runnable command) {
        if (shuttingDown)
            throw new RejectedExecutionException();

        this.tasks.add(command);
        if (!scheduled) {
            scheduled = true;
            base.submit(runner);  // if we haven't been scheduled yet, do so now
        }
    }

    private final Runnable runner = new Runnable() {
        public void run() {
            try {
                tasks.peek().run();
            } finally {
                synchronized (SingleLaneExecutorService.this) {
                    tasks.remove();// completed. this is needed because shutdown() looks at tasks.isEmpty()

                    assert scheduled;
                    if (!tasks.isEmpty()) {
                        // we have still more things to do
                        base.submit(this);
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
