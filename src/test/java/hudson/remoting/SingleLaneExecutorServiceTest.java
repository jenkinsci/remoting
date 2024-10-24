package hudson.remoting;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author Kohsuke Kawaguchi
 */
public class SingleLaneExecutorServiceTest extends Assert {
    ExecutorService base = Executors.newFixedThreadPool(5);
    ExecutorService lane1 = new SingleLaneExecutorService(base);
    ExecutorService lane2 = new SingleLaneExecutorService(base);

    @After
    public void tearDown() {
        base.shutdown();
    }

    /**
     * Schedule two heavy tasks in one lane, and one task in another lane.
     * The second lane should finish first and the first heavy lane should take turns.
     */
    @Test
    public void laneIndependence() throws Exception {
        final Object lock = new Object();
        final StringBuilder record = new StringBuilder();
        synchronized (lock) {
            lane1.submit(() -> {
                synchronized (lock) {
                }
                sleep(1000);
                record.append("x");
            });
            lane1.submit(() -> {
                record.append("y");
            });
            lane2.submit(() -> {
                record.append("z");
            });
        }
        waitForCompletion(lane1);
        waitForCompletion(lane2);

        assertEquals("zxy", record.toString());
    }

    /**
     * Tasks should execute in order even when there are a lot of capacities to execute them.
     */
    @Test
    public void fifo() throws Exception {
        final Random r = new Random(0);

        class Workload {
            List<Runnable> tasks = new LinkedList<>();
            StringBuilder record = new StringBuilder();
            ExecutorService lane = new SingleLaneExecutorService(base);

            Workload() {
                for (char t = 'a'; t <= 'z'; t++) {
                    final char ch = t;
                    tasks.add(() -> {
                        sleep(50 + r.nextInt(100));
                        record.append(ch);
                    });
                }
            }
        }

        List<Workload> works = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            works.add(new Workload());
        }

        // submit them all in the queue
        List<Workload> remaining = new ArrayList<>(works);
        int total = (('z' - 'a') + 1) * works.size();
        for (int i = 0; i < total; i++) {
            while (true) {
                int j = r.nextInt(remaining.size());
                Workload wl = remaining.get(j);
                if (!wl.tasks.isEmpty()) {
                    wl.lane.submit(wl.tasks.remove(0));
                    break;
                } else {
                    remaining.remove(wl);
                }
            }
        }

        // the execution order must have been preserved.
        for (Workload wl : works) {
            waitForCompletion(wl.lane);
            assertEquals("abcdefghijklmnopqrstuvwxyz", wl.record.toString());
        }
    }

    private void waitForCompletion(ExecutorService es) throws InterruptedException {
        es.shutdown();
        es.awaitTermination(5000, TimeUnit.MILLISECONDS);
    }

    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new Error();
        }
    }
}
