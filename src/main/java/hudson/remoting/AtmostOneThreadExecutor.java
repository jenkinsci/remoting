package hudson.remoting;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * {@link ExecutorService} that uses at most one executor.
 *
 * <p>
 * Compared to {@code Executors.newFixedThreadPool(1)}, this code will not keep
 * the thread around if it's not doing anything, freeing up resources.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.24
 */
public class AtmostOneThreadExecutor extends AbstractExecutorService {
    /**
     * The thread that actually runs the work.
     */
    private Thread worker;

    private final LinkedList<Runnable> q = new LinkedList<Runnable>();

    private boolean shutdown;

    private final ThreadFactory factory;

    public AtmostOneThreadExecutor(ThreadFactory factory) {
        this.factory = factory;
    }

    public AtmostOneThreadExecutor() {
        this(new DaemonThreadFactory());
    }

    public void shutdown() {
        synchronized (q) {
            shutdown = true;
            if (isAlive())
                worker.interrupt();
        }
    }

    /**
     * Do we have a worker thread and is it running?
     */
    private boolean isAlive() {
        return worker!=null && worker.isAlive();
    }

    public List<Runnable> shutdownNow() {
        synchronized (q) {
            shutdown = true;
            List<Runnable> r = new ArrayList<Runnable>(q);
            q.clear();
            return r;
        }
    }

    public boolean isShutdown() {
        return shutdown;
    }

    public boolean isTerminated() {
        return shutdown && !isAlive();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        synchronized (q) {
            long now = System.nanoTime();
            long end = now + unit.toNanos(timeout);
            while (isAlive() && (end - now > 0L)) {
                q.wait(TimeUnit.NANOSECONDS.toMillis(end - now));
                now = System.nanoTime();
            }
        }
        return isTerminated();
    }

    public void execute(Runnable command) {
        synchronized (q) {
            if (isShutdown()) {
                throw new RejectedExecutionException("This executor has been shutdown.");
            }
            q.add(command);
            if (!isAlive()) {
                worker = factory.newThread(new Worker());
                worker.start();
            }
        }
    }

    private class Worker implements Runnable {
        public void run() {
            while (true) {
                Runnable task;
                synchronized (q) {
                    if (q.isEmpty()) {// no more work
                        worker = null;
                        return;
                    }
                    task = q.remove();
                }
                task.run();
            }
        }
    }
}
