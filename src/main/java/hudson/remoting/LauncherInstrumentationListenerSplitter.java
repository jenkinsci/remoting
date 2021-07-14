package hudson.remoting;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class LauncherInstrumentationListenerSplitter implements LauncherInstrumentationListener {
    protected final List<LauncherInstrumentationListener> listeners = new CopyOnWriteArrayList<>();

    public void add(LauncherInstrumentationListener ll) {
        listeners.add(ll);
    }

    public void remove(LauncherInstrumentationListener ll) {
        listeners.remove(ll);
    }

    @Override
    public void onLaunch(Launcher launcher) {
        for (LauncherInstrumentationListener l : listeners) l.onLaunch(launcher);
    }

    @Override
    public void onRunWithStdinStdout() {
        for (LauncherInstrumentationListener l : listeners) l.onRunWithStdinStdout();
    }

    @Override
    public void onConnected() {
        for (LauncherInstrumentationListener l : listeners) l.onConnected();
    }
}
