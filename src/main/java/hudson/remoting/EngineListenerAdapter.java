package hudson.remoting;

/**
 * Adapter class for {@link EngineListener} to shield subtypes from future callback additions.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.36
 */
public abstract class EngineListenerAdapter implements EngineListener {
    @Override
    public void status(String msg) {}

    @Override
    public void status(String msg, Throwable t) {}

    @Override
    public void error(Throwable t) {}

    @Override
    public void onDisconnect() {}

    @Override
    public void onReconnect() {}
}
