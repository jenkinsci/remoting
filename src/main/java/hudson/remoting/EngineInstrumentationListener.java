package hudson.remoting;

/*package*/ interface EngineInstrumentationListener {
    void onStart(Engine engine, boolean webSocket);
    void onConnected();
}
