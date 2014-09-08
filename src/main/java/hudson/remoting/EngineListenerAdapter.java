package hudson.remoting;

/**
 * Adapter class for {@link EngineListener} to shield subtypes from future callback additions.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.36
 */
public abstract class EngineListenerAdapter implements EngineListener {
    public void status(String msg) {
    }

    public void status(String msg, Throwable t) {
    }

    public void error(Throwable t) {
    }

    public void onDisconnect() {
    }

    public void onReconnect() {
    }
}
