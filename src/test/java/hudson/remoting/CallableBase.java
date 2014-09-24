package hudson.remoting;

import org.jenkinsci.remoting.Role;

import java.util.Collection;
import java.util.Collections;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class CallableBase<V,T extends Throwable> implements Callable<V,T> {
    @Override
    public Collection<Role> getRecipients() {
        return Collections.singleton(ROLE);
    }

    public static final Role ROLE = new Role("test callable");
}
