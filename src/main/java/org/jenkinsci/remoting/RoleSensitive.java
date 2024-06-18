package org.jenkinsci.remoting;

import hudson.remoting.Callable;
import java.util.Collection;

/**
 * Used by {@link Callable}-like objects to designate the intended recipient of the callable,
 * to help verify callables are running in JVMs that it is intended to run.
 *
 * <p>
 * This interface is defined separately from {@link Callable} so that other callable-like interfaces
 * can reuse this.
 *
 * @author Kohsuke Kawaguchi
 * @see RoleChecker
 * @since 2.47
 */
public interface RoleSensitive {
    /**
     * Verifies the roles expected by this callable by invoking {@link RoleChecker#check(RoleSensitive, Collection)}
     * method (or its variants), to provide an opportunity for {@link RoleChecker} to reject this object.
     * <p><strong>Do not implement this method</strong> unless you know what you are doing.
     * If you have a Jenkins {@link Callable} or {@code FileCallable}, use the standard abstract base classes instead,
     * such as {@code MasterToSlaveCallable}, {@code MasterToSlaveFileCallable}, {@code NotReallyRoleSensitiveCallable}, etc.
     * See <a href="https://wiki.jenkins-ci.org/display/JENKINS/Slave+To+Master+Access+Control/#SlaveToMasterAccessControl-I%27maplugindeveloper.WhatshouldIdo%3F">this document</a> for details.
     * <p>
     * If the method returns normally, the check has passed.
     *
     * @throws SecurityException
     *      If there's a mismatch in the expected roles and the actual roles that should prevent
     *      the execution of this callable.
     *
     * @throws AbstractMethodError
     *      In the history of this library, this interface was added rather later, so there's lots of
     *      {@link Callable}s out there that do not implement this method.
     *      For this reason, code that calls this method should be prepared to
     *      receive {@link AbstractMethodError}, and treat that as if the invocation of
     *      {@code checker.check(this,Role.UNKNOWN)} has happened.
     */
    void checkRoles(RoleChecker checker) throws SecurityException;
}
