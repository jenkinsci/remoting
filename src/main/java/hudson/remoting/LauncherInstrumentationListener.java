package hudson.remoting;

/*package*/ interface LauncherInstrumentationListener {
    void onLaunch(Launcher launcher);
    void onRunWithStdinStdout();
    void onConnected();
}
