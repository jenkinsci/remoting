package hudson.remoting;

import org.jvnet.animal_sniffer.IgnoreJRERequirement;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Generates diagnosis information (threaddump + stacktrace) when the pingThread fails
 *
 * <p>
 * Useful to troubleshoot agent disconnection failures
 *
 * @since 2.55
 */
public class AgentFailuresAnalyzer {

    /**
     * Channel we are going to study
     */
    private Channel c;

    /**
     * The cause of the ping failure
     */
    private Throwable cause;


    /**
     * Maximum of files to store inside agentFailuresAnalyzer/thread-dumps and pingFailureAnalyzer/stacktraces.
     * When the limit is reached out it starts overriding.
     */
    private final static int MAX_DIAGNOSIS_FILES = 5;

    /**
     * Folder to store the thread dumps. Maintains most recent N files in a directory in cooperation with the writer.
     */
    private final AgentFailuresFileListCap threadDumps = new AgentFailuresFileListCap(new File("agentFailuresAnalyzer/thread-dumps"), MAX_DIAGNOSIS_FILES);

    /**
     * Folder to store the stacktraces. Maintains most recent N files in a directory in cooperation with the writer.
     */
    private final AgentFailuresFileListCap stacktraces = new AgentFailuresFileListCap(new File("agentFailuresAnalyzer/stacktraces"), MAX_DIAGNOSIS_FILES);

    public AgentFailuresAnalyzer(Channel c, Throwable cause) {
        this.c = c;
        this.cause = cause;
    }


    /**
     * Save the stacktrace which produces the ping failure into agentFailuresAnalyzer/stacktraces
     *
     * @param cause
     *      The Throwable
     */
    public void saveStackTrace(Throwable cause) throws FileNotFoundException, UnsupportedEncodingException {
        long iota = System.currentTimeMillis();
        final SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss.SSS");
        File file = stacktraces.file("stacktrace-" + format.format(new Date(iota)) + ".txt");
        stacktraces.add(file);
        PrintWriter writer = null;
        try {
            writer = new PrintWriter(file, "UTF-8");
        } catch (FileNotFoundException e) {
            throw e;
        } catch (UnsupportedEncodingException e) {
            throw e;
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
        if (cause != null) {
            cause.printStackTrace(writer);
        }
    }

    /**
     * Dumps all of the threads' current information to an output stream.
     *
     */
    @IgnoreJRERequirement
    @edu.umd.cs.findbugs.annotations.SuppressWarnings(
            value = {"VA_FORMAT_STRING_USES_NEWLINE"},
            justification = "We don't want platform specific"
    )
    public void takeThreadDump() throws FileNotFoundException, UnsupportedEncodingException {
        long iota = System.currentTimeMillis();
        final SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss.SSS");
        File file = threadDumps.file(format.format("threadDump-" + new Date(iota)) + ".txt");
        threadDumps.add(file);

        PrintWriter writer;
        try {
            writer = new PrintWriter(file, "UTF-8");
        } catch (FileNotFoundException e) {
            throw e;
        } catch (UnsupportedEncodingException e) {
            throw e;
        }

        ThreadMXBean mbean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] threads;
        try {
            threads = mbean.dumpAllThreads(mbean.isObjectMonitorUsageSupported(), mbean.isSynchronizerUsageSupported());
        } catch (UnsupportedOperationException x) {
            x.printStackTrace(writer);
            threads = new ThreadInfo[0];
        }

        for (int ti = threads.length - 1; ti >= 0; ti--) {
            printThreadInfo(writer, threads[ti], mbean);
        }

        // Print any information about deadlocks.
        long[] deadLocks;
        try {
            deadLocks = mbean.findDeadlockedThreads();
        } catch (UnsupportedOperationException x) {
            x.printStackTrace(writer);
            deadLocks = null;
        }
        if (deadLocks != null && deadLocks.length != 0) {
            writer.println(" Deadlock Found ");
            ThreadInfo[] deadLockThreads = mbean.getThreadInfo(deadLocks);
            for (ThreadInfo threadInfo : deadLockThreads) {
                StackTraceElement[] elements = threadInfo.getStackTrace();
                for (StackTraceElement element : elements) {
                    writer.println(element.toString());
                }
            }
        }
        writer.println();
        writer.flush();
        writer.close();
    }

    /**
     * Prints the {@link ThreadInfo} (because {@link ThreadInfo#toString()} caps out the stack trace at 8 frames)
     *
     * @param writer the writer to print to.
     * @param t      the thread to print
     * @param mbean  the {@link ThreadMXBean} to use.
     */
    @edu.umd.cs.findbugs.annotations.SuppressWarnings(
            value = {"VA_FORMAT_STRING_USES_NEWLINE"},
            justification = "We don't want platform specific"
    )
    public static void printThreadInfo(PrintWriter writer, ThreadInfo t, ThreadMXBean mbean) {
        writer.printf("\"%s\" id=%d (0x%x) state=%s",
                t.getThreadName(),
                t.getThreadId(),
                t.getThreadId(),
                t.getThreadState());
        final LockInfo lock = t.getLockInfo();
        if (lock != null && t.getThreadState() != Thread.State.BLOCKED) {
            writer.printf("\n    - waiting on <0x%08x> (a %s)",
                    lock.getIdentityHashCode(),
                    lock.getClassName());
            writer.printf("\n    - locked <0x%08x> (a %s)",
                    lock.getIdentityHashCode(),
                    lock.getClassName());
        } else if (lock != null && t.getThreadState() == Thread.State.BLOCKED) {
            writer.printf("\n    - waiting to lock <0x%08x> (a %s)",
                    lock.getIdentityHashCode(),
                    lock.getClassName());
        }

        if (t.isSuspended()) {
            writer.print(" (suspended)");
        }

        if (t.isInNative()) {
            writer.print(" (running in native)");
        }

        writer.println();
        if (t.getLockOwnerName() != null) {
            writer.printf("      owned by \"%s\" id=%d (0x%x)\n",
                    t.getLockOwnerName(),
                    t.getLockOwnerId(),
                    t.getLockOwnerId());
        }

        final StackTraceElement[] elements = t.getStackTrace();
        final MonitorInfo[] monitors = t.getLockedMonitors();

        for (int i = 0; i < elements.length; i++) {
            final StackTraceElement element = elements[i];
            writer.printf("    at %s\n", element);
            for (int j = 1; j < monitors.length; j++) {
                final MonitorInfo monitor = monitors[j];
                if (monitor.getLockedStackDepth() == i) {
                    writer.printf("      - locked %s\n", monitor);
                }
            }
        }
        writer.println();

        final LockInfo[] locks = t.getLockedSynchronizers();
        if (locks.length > 0) {
            writer.printf("    Locked synchronizers: count = %d\n", locks.length);
            for (LockInfo l : locks) {
                writer.printf("      - %s\n", l);
            }
            writer.println();
        }
    }

}
