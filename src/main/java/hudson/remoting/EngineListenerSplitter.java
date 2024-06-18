package hudson.remoting;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link EngineListener} that distributes callbacks.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.36
 */
public class EngineListenerSplitter implements EngineListener {
    protected final List<EngineListener> listeners = new CopyOnWriteArrayList<>();

    public void add(EngineListener el) {
        listeners.add(el);
    }

    public void remove(EngineListener el) {
        listeners.remove(el);
    }

    @Override
    public void status(String msg) {
        for (EngineListener l : listeners) {
            l.status(msg);
        }
    }

    @Override
    public void status(String msg, Throwable t) {
        for (EngineListener l : listeners) {
            l.status(msg, t);
        }
    }

    @Override
    public void error(Throwable t) {
        for (EngineListener l : listeners) {
            l.error(t);
        }
    }

    @Override
    public void onDisconnect() {
        for (EngineListener l : listeners) {
            l.onDisconnect();
        }
    }

    @Override
    public void onReconnect() {
        for (EngineListener l : listeners) {
            l.onReconnect();
        }
    }
}
