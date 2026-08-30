package hudson.remoting;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.Issue;

/**
 * Regression test for a lock-ordering deadlock between {@link Channel} and {@link ProxyOutputStream}.
 *
 * <p>{@link ProxyOutputStream}'s {@code synchronized} methods ({@code write}, {@code flush},
 * {@code close}, {@code error}) call {@link Channel#send} which is {@code synchronized} on the
 * {@link Channel}, so they acquire the stream monitor and then the channel monitor.
 * {@link Channel#terminate} used to hold the channel monitor while calling
 * {@link ExportTable#abort} which calls {@link ErrorPropagatingOutputStream#error} on exported
 * {@link ProxyOutputStream}s, acquiring the two monitors in the opposite order. A concurrent write
 * and termination could therefore deadlock.
 */
class ChannelTerminateDeadlockTest {

    @Test
    @Timeout(60)
    @Issue("JENKINS-66526")
    void terminateDoesNotDeadlockAgainstProxyOutputStreamWrite() throws Exception {
        InProcessRunner runner = new InProcessRunner();
        Channel channel = runner.start();
        try {
            // A real ProxyOutputStream registered in the channel's export table, as would exist for
            // any RemoteOutputStream handed to the far side.
            ProxyOutputStream pos = new ProxyOutputStream();
            int oid = channel.exportedObjects.export(OutputStream.class, pos, false);
            pos.connect(channel, oid);

            CountDownLatch writerHoldsStream = new CountDownLatch(1);
            CountDownLatch releaseWriter = new CountDownLatch(1);

            // Writer thread: holds the ProxyOutputStream monitor, then attempts an operation that
            // needs the Channel monitor (via Channel.send()).
            Thread writer = new Thread(
                    () -> {
                        synchronized (pos) {
                            writerHoldsStream.countDown();
                            try {
                                releaseWriter.await();
                                pos.flush(); // -> Channel.send() -> needs Channel monitor
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (IOException e) {
                                // Expected once the channel is terminated; not a deadlock.
                            }
                        }
                    },
                    "deadlock-test-writer");
            writer.setDaemon(true);

            // Terminator thread: takes the Channel monitor and, via ExportTable.abort(), calls
            // pos.error() which needs the ProxyOutputStream monitor.
            Thread terminator = new Thread(
                    () -> channel.terminate(new IOException("test-induced termination")), "deadlock-test-terminator");
            terminator.setDaemon(true);

            writer.start();
            writerHoldsStream.await();

            terminator.start();
            // Wait until the terminator is blocked trying to acquire the stream monitor held by the writer.
            awaitBlockedOn(terminator, writer);

            // Now let the writer try to acquire the Channel monitor. On the buggy code the terminator
            // holds it while blocked on the stream monitor, so this closes the cycle.
            releaseWriter.countDown();

            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                long[] deadlocked = bean.findDeadlockedThreads();
                if (deadlocked != null) {
                    fail("Deadlock detected between Channel.terminate() and ProxyOutputStream:\n"
                            + dump(bean, deadlocked));
                }
                if (!writer.isAlive() && !terminator.isAlive()) {
                    break;
                }
                Thread.sleep(50);
            }

            writer.join(TimeUnit.SECONDS.toMillis(5));
            terminator.join(TimeUnit.SECONDS.toMillis(5));
            assertNull(bean.findDeadlockedThreads(), "threads still deadlocked at end of test");
            if (writer.isAlive() || terminator.isAlive()) {
                fail("threads did not complete; likely deadlocked:\n"
                        + dump(bean, new long[] {writer.getId(), terminator.getId()}));
            }
        } finally {
            // Best-effort teardown on a bounded background thread: if the fix regresses, the helper
            // threads are deadlocked holding the Channel monitor and runner.stop() would block forever.
            Thread cleanup = new Thread(
                    () -> {
                        try {
                            runner.stop(channel);
                        } catch (Exception ignored) {
                            // Already terminated; south-side teardown noise is irrelevant here.
                        }
                    },
                    "deadlock-test-cleanup");
            cleanup.setDaemon(true);
            cleanup.start();
            cleanup.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    private static void awaitBlockedOn(Thread blocked, Thread lockOwner) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        while (System.nanoTime() < deadline) {
            ThreadInfo info = bean.getThreadInfo(blocked.getId());
            if (info != null
                    && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == lockOwner.getId()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(blocked.getName() + " never blocked on a monitor held by " + lockOwner.getName());
    }

    private static String dump(ThreadMXBean bean, long[] ids) {
        StringBuilder sb = new StringBuilder();
        for (ThreadInfo info : bean.getThreadInfo(ids, true, true)) {
            if (info != null) {
                sb.append(info);
            }
        }
        return sb.toString();
    }
}
