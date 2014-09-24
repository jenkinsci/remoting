package hudson.remoting.util;

import hudson.remoting.CallableBase;

import java.io.IOException;

/**
 * @author Kohsuke Kawaguchi
 */
public class GCTask extends CallableBase<Object, IOException> {
    public Object call() throws IOException {
        System.gc();
        return null;
    }
}
