package hudson.remoting;

/**
 * @author Kohsuke Kawaguchi
 */
public class InProcessCompatibilityRunner extends InProcessRunner {
    @Override
    public String getName() {
        return "local-compatibility";
    }

    @Override
    protected Capability createCapability() {
        return Capability.NONE;
    }
}
