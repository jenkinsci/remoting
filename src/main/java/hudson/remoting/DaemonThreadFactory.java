package hudson.remoting;

import javax.annotation.Nonnull;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Kohsuke Kawaguchi
 */
public class DaemonThreadFactory implements ThreadFactory {
    private static final Logger LOGGER = Logger.getLogger(DaemonThreadFactory.class.getName());

    @Override
    public Thread newThread(@Nonnull Runnable r) {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((t, e) -> LOGGER.log(Level.SEVERE, e, () -> "Unhandled exception in thread " + t));
        return thread;
    }
}
