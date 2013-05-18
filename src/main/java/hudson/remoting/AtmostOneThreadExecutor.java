package hudson.remoting;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link ExecutorService} that uses at most one executor.
 *
 * @author Kohsuke Kawaguchi
 */
public class AtmostOneThreadExecutor extends AbstractExecutorService {
    private Thread worker;

    private final LinkedList<Runnable> q = new LinkedList<Runnable>();

    private boolean shutdown;

    public void shutdown() {
        shutdown = true;
        synchronized (q) {
            if (isAlive())
                worker.interrupt();
        }
    }

    private boolean isAlive() {
        return worker!=null && worker.isAlive();
    }

    public List<Runnable> shutdownNow() {
        synchronized (q) {
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
            long start = System.currentTimeMillis();
            long end = start+unit.toMillis(timeout);
            while (isAlive() && System.currentTimeMillis()<end) {
                q.wait(end-System.currentTimeMillis());
            }
        }
        return isTerminated();
    }

    public void execute(Runnable command) {
        synchronized (q) {
            q.add(command);
            if (!isAlive()) {
                worker = new Worker();
                worker.start();
            }
        }
    }

    private class Worker extends Thread {
        public Worker() {
            super();
            setDaemon(true);
        }

        @Override
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
