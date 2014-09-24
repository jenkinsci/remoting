package org.jenkinsci.remoting;

import hudson.remoting.Callable;
import hudson.remoting.ChannelBuilder;

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * Verifies that the callable is getting run on the intended recipient.
 *
 * @author Kohsuke Kawaguchi
 * @see ChannelBuilder#withRoleChecker(RoleChecker)
 * @since TODO
 */
public abstract class RoleChecker {
    /**
     * Called when we receive {@link Callable} to ensure that this side of the channel is willing to execute
     * {@link Callable}s that have the given roles as their intended recipients.
     *
     * <p>
     * Normally, each side of the channel has a fixed set of roles (say {@code actualRoles}),
     * and the implementation would be {@code actualRoles.containsAll(roles)}.
     *
     * @param subject
     *      Object whose role we are checking right now.
     * @param roles
     *      The value returned from {@link Callable#getRecipients()}.
     * @throws SecurityException
     *      Any exception thrown will prevent the callable from getting executed, but we recommend
     *      {@link SecurityException}
     */
    public abstract void check(@Nonnull RoleSensitive subject, @Nonnull Collection<Role> roles) throws SecurityException;
}
