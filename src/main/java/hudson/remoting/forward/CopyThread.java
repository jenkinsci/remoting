package hudson.remoting.forward;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Copies a stream and close them at EOF.
 *
 * @author Kohsuke Kawaguchi
 */
final class CopyThread extends Thread {
    private static final Logger LOGGER = Logger.getLogger(CopyThread.class.getName());
    private final InputStream in;
    private final OutputStream out;

    /**
     * Callers are responsible for closing the input and output streams.
     */
    public CopyThread(String threadName, InputStream in, OutputStream out, Runnable termination) {
        this(threadName, in, out, termination, 5);
    }

    private CopyThread(String threadName, InputStream in, OutputStream out, Runnable termination, int remainingTries) {
        super(threadName);
        this.in = in;
        this.out = out;
        setUncaughtExceptionHandler((t, e) -> {
            if (remainingTries > 0) {
                LOGGER.log(Level.WARNING, "Uncaught exception in CopyThread " + t + ", retrying copy", e);
                new CopyThread(threadName, in, out, termination, remainingTries - 1).start();
            } else {
                LOGGER.log(Level.SEVERE, "Uncaught exception in CopyThread " + t + ", out of retries", e);
                termination.run();
            }
        });
    }

    @Override
    public void run() {
        try {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0)
                out.write(buf, 0, len);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Exception while copying in thread: " + getName(), e);
        }
    }
}
