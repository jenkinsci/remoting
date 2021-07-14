package hudson.remoting;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EngineInstrumentationListenerSplitter implements EngineInstrumentationListener {
    protected final List<EngineInstrumentationListener> listeners = new CopyOnWriteArrayList<>();

    public void add(EngineInstrumentationListener eil) {
        listeners.add(eil);
    }

    public void remove(EngineInstrumentationListener eil) {
        listeners.remove(eil);
    }

    @Override
    public void onStart(Engine engine, boolean webSocket) {
        for (EngineInstrumentationListener l : listeners) {
            l.onStart(engine, webSocket);
        }
    }

    @Override
    public void onConnected() {
        for (EngineInstrumentationListener l : listeners) {
            l.onConnected();
        }
    }
}
