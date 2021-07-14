package hudson.remoting;

public class EngineInstrumentationListenerAdapter implements EngineInstrumentationListener {
    @Override
    public void onStart(Engine engine, boolean webSocket) {}

    @Override
    public void onConnected() {}
}
