package test;

import com.google.common.base.Function;
import hudson.remoting.Callable;

/**
 * @author Kohsuke Kawaguchi
 */
public class ClassLoadingFromJarTester implements Callable<Object,Exception>, Function<Function,Void> {
    public Function verifier;

    public Object call() throws Exception {
        // verify that the tester is loaded into the correct state
        return verifier.apply(this);
    }

    // just so that we can set the delegate without reflection
    public Void apply(Function function) {
        this.verifier = function;
        return null;
    }
}
