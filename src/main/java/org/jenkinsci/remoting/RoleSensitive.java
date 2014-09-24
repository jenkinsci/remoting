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
 * @since TODO
 */
public interface RoleSensitive {
    /**
     * Returns {@link Role}s that are expected by the JVM that executes this callable.
     *
     * <p>
     * When multiple values are in the set, the intended recipient is one that can play all the roles
     * (that is, the callable is a composite of a number of smaller callables each designating different
     * recipient.)
     *
     * @return
     *      Never null (but if you see it, treat as {@link Role#UNKNOWN_SET}.)
     *      Empty collection is legal, but it should be used with caution sparingly. It indicates that
     *      the recipient can be anyone and the callable is safe to execute everywhere.
     *      OTOH, it robs the ability for {@link RoleChecker} to refuse execution.
     *
     * @throws AbstractMethodError
     *      In the history of this library, this interface was added rather later, so there's lots of
     *      {@link Callable}s out there that do not implement this method.
     *      For this reason, code that calls this method should be prepared to
     *      receive {@link AbstractMethodError}, and treat that as if this method is returning
     *      {@link Role#UNKNOWN_SET}
     */
    Collection<Role> getRecipients();
}
