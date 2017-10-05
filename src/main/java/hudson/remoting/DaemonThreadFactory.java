package hudson.remoting;

import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Kohsuke Kawaguchi
 */
public class DaemonThreadFactory implements ThreadFactory {
    private static final Logger LOGGER = Logger.getLogger(DaemonThreadFactory.class.getName());

    public Thread newThread(Runnable r) {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((t, e) -> LOGGER.log(Level.SEVERE, "Unhandled exception in thread " + t, e));
        return thread;
    }
}
