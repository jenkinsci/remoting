package hudson.remoting.util;

import hudson.remoting.CallableBase;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Kohsuke Kawaguchi
 */
public class GCTask extends CallableBase<Object, IOException> {
    private final boolean agressive;

    public GCTask() {
        this(false);
    }

    public GCTask(boolean agressive) {
        this.agressive = agressive;
    }

    @Override
    public Object call() throws IOException {
        if (agressive) {
            Set<Object[]> objects = new HashSet<>();
            int size = ((int) Math.min(Runtime.getRuntime().freeMemory(), Integer.MAX_VALUE)) / 32;
            while (true) {
                try {
                    objects.add(new Object[size]);
                } catch (OutOfMemoryError ignore) {
                    break;
                }
            }
        }
        System.gc();
        return null;
    }
}
