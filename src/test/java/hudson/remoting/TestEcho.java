package hudson.remoting;

import hudson.remoting.Bug20707Test.IEcho;

import java.io.IOException;
import java.lang.ref.WeakReference;

/**
* @author Kohsuke Kawaguchi
*/
public class TestEcho implements IEcho {
    /**
     * For adding arbitrary objects into the echo back.
     */
    private Object o;

    public TestEcho(Object o) {
        this.o = o;
    }

    public TestEcho() {
    }

    public Object call() throws IOException {
        Bug20707Test.set(new WeakReference<ClassLoader>(getClass().getClassLoader()));
        return this;
    }

    public void set(Object o) {
        this.o = o;
    }

    public Object get() {
        return o;
    }

    private static final long serialVersionUID = 1L;
}
