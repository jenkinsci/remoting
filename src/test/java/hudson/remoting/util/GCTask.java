package hudson.remoting.util;

import hudson.remoting.Callable;

import java.io.IOException;

/**
 * @author Kohsuke Kawaguchi
 */
public class GCTask implements Callable<Object, IOException> {
    public Object call() throws IOException {
        System.gc();
        return null;
    }
}
