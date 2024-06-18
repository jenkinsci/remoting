package hudson.remoting;

import org.jenkinsci.remoting.Role;
import org.jenkinsci.remoting.RoleChecker;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class CallableBase<V, T extends Throwable> implements Callable<V, T> {
    @Override
    public void checkRoles(RoleChecker checker) throws SecurityException {
        checker.check(this, ROLE);
    }

    public static final Role ROLE = new Role("test callable");
    private static final long serialVersionUID = 1L;
}
