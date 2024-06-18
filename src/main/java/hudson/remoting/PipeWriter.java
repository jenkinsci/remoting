package hudson.remoting;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * I/O task scheduler.
 *
 * <p>
 * Remoting handles I/O in a separate thread to prevent deadlock (JENKINS-5977), but that means occasionally
 * we need to synchronize between {@link Command}s and I/O (JENKINS-9189, JENKINS-7871, JENKINS-11251.)
 *
 * This class controls the task submission to I/O thread, and provides means to synchronize with it as follows:
 *
 * <ol>
 *     <li>
 *     The sending {@link Channel} assigns an unique I/O ID (via {@link Channel#ioId} to every I/O {@link Command}
 *     that it sends out. It also remembers the last I/O ID issued by the thread via {@link Channel#lastIoId}.
 *
 *     <li>
 *     The receiving {@link Channel} uses that in {@link PipeWriter#submit(int, Runnable)} to enable re-discovery later.
 *
 *     <li>
 *     {@link Future}s are maintained and made discoverable by their I/O ID as I/O operations take place.
 *
 *     <li>
 *     When sending {@link Channel} later issues a {@link Request}, it uses {@link Channel#lastIoId} to recover
 *     which I/O operation needs to take place before the {@link Request} can happen.
 *
 *     <li>
 *     The receiving {@link Channel} honors that before it gets {@link Request}.
 *
 *     <li>
 *     By the same token, the receiving {@link Channel} also records what I/O operations are issued by the
 *     closure sent by {@link Request}. When it sends back {@link Response}, it also sends out the last I/O ID
 *     issued by the closure ({@link Response#lastIoId}.
 *
 *     <li>
 *     {@link Request} on the sending {@link Channel} honors this "last I/O ID" before it returns with the
 *     response.
 * </ol>
 *
 * I/O ID tracking and synchronization is per thread. This prevents the regression of JENKINS-5977.
 *
 * <h2>Backward Compatibility</h2>
 * <p>
 * When one side (say sender) predates I/O ID, the other side sees all I/O IDs as 0. So the receiver won't actually does the
 * {@link Future} book-keeping, and it will not wait for any I/O operations on its side, thanks to 0 being a special value.
 * Similarly, all the I/O ID tracking the receiver does get ignored by the sender.
 *
 * <h2>Motivation</h2>
 * <p>
 * This change is motivated by the fact that a certain degree of synchronization between {@link Request}/{@link Response}s
 * and I/O operations are desirable. The original issue was JENKINS-9189, which had the following:
 *
 * <pre>
 * OutputStream os = new RemoteOutputStream(...);
 * channel.call(new Callable() {
 *      os.write(...);
 * });
 * os.close();
 * </pre>
 *
 * <p>
 * The normal expectation is that by the time closure returns, all 'os.write()' operations are completed.
 * Yet since I/O and commands are separate, so unless the remoting library does synchronization,
 * 'os.close()' can happen before some of 'os.write()' calls, and ends up in a file truncation.
 *
 * <p>
 * That was fixed by 9cdd9cc0c5640beeb6bf36a4b26fa1ddcce7fd60 in the core originally, by adding
 * synchronization between I/O calls and {@link Response}, but then later
 * we discovered JENKINS-11251, which basically boils down to the following:
 *
 * <pre>
 * FilePath f = ...;
 * OutputStream os = f.write()
 * IOUtils.copy(data,os)
 * os.close()
 *
 * f.act(new Callable() {
 *     ... act on this newly created file ...
 * });
 * </pre>
 *
 * <p>
 * This now requires {@link Response} and I/O call coordination.
 *
 * <p>
 * This I/O ID based approach unifies both kind of coordination.
 *
 * @author Kohsuke Kawaguchi
 */
class PipeWriter {
    private static final class FutureHolder {
        private Future<?> f;

        public synchronized Future<?> set(Future<?> f) {
            this.f = f;
            notifyAll();
            return f;
        }

        public synchronized Future<?> get() throws InterruptedException {
            while (f == null) {
                wait();
            }
            return f;
        }
    }

    private final Map<Integer, FutureHolder> pendingIO = Collections.synchronizedMap(new HashMap<>());

    /**
     * Actually carries out the {@link Runnable}s.
     */
    private final ExecutorService base;

    private final AtomicInteger iota = new AtomicInteger();

    public PipeWriter(ExecutorService base) {
        this.base = base;
    }

    public void shutdown() {
        base.shutdown();
    }

    /**
     * @param id
     *      I/O ID that used later in {@link #get(int)}. The value 0 has a special meaning
     *      that indicates that no sync is needed later. Otherwise the caller is responsible
     *      for assigning unique values.
     *
     * @return
     *      Future object that can be used to wait for the completion of the submitted I/O task.
     */
    public Future<?> submit(final int id, final Runnable command) {
        if (id == 0) {
            return base.submit(command);
        }

        // this indirection is needed to ensure that put happens before remove
        // if we store Future itself, then remove can happen before put, and
        // we'll end up leaking
        FutureHolder fh = new FutureHolder();

        FutureHolder old = pendingIO.put(id, fh);
        assert old == null;

        return fh.set(base.submit(new Runnable() {
            @Override
            public void run() {
                final Thread t = Thread.currentThread();
                final String oldName = t.getName();
                try {
                    t.setName(oldName + " : IO ID=" + id + " : seq#=" + iota.getAndIncrement());
                    command.run();
                } finally {
                    FutureHolder old = pendingIO.remove(id);
                    assert old != null;
                    t.setName(oldName);
                }
            }
        }));
    }

    /**
     * Gets the {@link Future} object that can be used to wait for the completion of the submitted I/O task.
     *
     * Unlike POSIX wait() call, {@link PipeWriter} doesn't require that someone waits for the completion
     * of an I/O task. The consequence of that is that {@link PipeWriter} cannot differentiate between
     * invalid ID vs ID that was used and completed long time ago. In both cases, a {@link Future} object
     * that's already in the signaled state is returned.
     *
     * @return
     *      never null. Unlike normal contract of {@link Future}, the object returned from this method
     *      cannot necessarily be able to distinguish the normal and abnormal completion of the task.
     */
    public Future<?> get(int id) throws InterruptedException {
        FutureHolder f = pendingIO.get(id);
        if (f == null) {
            return SIGNALED; // already completed
        }
        return f.get();
    }

    private static final Future<?> SIGNALED = new AsyncFutureImpl<>(new Object());
}
