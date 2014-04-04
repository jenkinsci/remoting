package hudson.remoting;

/**
* @author Kohsuke Kawaguchi
*/
public class InProcessCompatibilityRunner extends InProcessRunner {
    public String getName() {
        return "local-compatibility";
    }

    @Override
    protected Capability createCapability() {
        return Capability.NONE;
    }
}
