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
    protected final List<EngineListener> listeners = new CopyOnWriteArrayList<EngineListener>();

    public void add(EngineListener el) {
        listeners.add(el);
    }

    public void remove(EngineListener el) {
        listeners.remove(el);
    }

    public void status(String msg) {
        for (EngineListener l : listeners) {
            l.status(msg);
        }
    }

    public void status(String msg, Throwable t) {
        for (EngineListener l : listeners) {
            l.status(msg,t);
        }
    }

    public void error(Throwable t) {
        for (EngineListener l : listeners) {
            l.error(t);
        }
    }

    public void onDisconnect() {
        for (EngineListener l : listeners) {
            l.onDisconnect();
        }
    }

    public void onReconnect() {
        for (EngineListener l : listeners) {
            l.onReconnect();
        }
    }
}
