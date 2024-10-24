package org.jenkinsci.remoting;

import hudson.remoting.Callable;
import hudson.remoting.Channel;
import java.util.Collection;
import java.util.Set;

/**
 * Represents different roles two sides of the channel plays.
 *
 * <p>
 * Often the communication on {@link Channel} is asymmetric (for example, one side acts like a server
 * and the other side acts like a client), and therefore it is useful to be able to mark
 * {@link Callable} with the intended parties that are supposed to run them. This in turn
 * allows parties to verify that it is not running {@link Callable}s that it is not supposed to be executing.
 *
 * <p>
 * {@link Role}s are compared based on the instance equality, so typically they'd be instantiated as singletons.
 * For example, if you are designing a client/server protocol, you would have two role instances like this:
 *
 * <pre>
 * public class MyProtocol {
 *     public static final Role SERVER = new Role("server");
 *     public static final Role CLIENT = new Role("client");
 * }
 * </pre>
 *
 * <p>
 * Then the callables that are meant to be run on the client would check {@code CLIENT} from
 * {@link RoleSensitive#checkRoles(RoleChecker)}:
 *
 * <pre>
 * // from the server
 * channelToClient.call(new Callable&lt;Void,IOException&gt;() {
 *     Void call() {
 *         ...
 *     }
 *     void checkRoles(RoleChecker checker) {
 *          checker.check(this,MyProtocol.CLIENT);
 *     }
 * });
 * </pre>
 *
 *
 * @author Kohsuke Kawaguchi
 * @see RoleSensitive
 * @see RoleChecker
 */
public final class Role {
    private final String name;

    public Role(String name) {
        this.name = name;
    }

    public Role(Class<?> name) {
        this(name.getName());
    }

    /**
     * Gets a human readable name of this role.
     *
     * Names are not a formal part of the role equality. That is,
     * two {@link Role} instances are considered different even if they have the same name.
     */
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return super.toString() + "[" + name + "]";
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Role && ((Role) obj).name.equals(name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /**
     * Used as a place holder when {@link Callable} didn't declare any role.
     */
    public static final Role UNKNOWN = new Role("unknown");

    /**
     * Convenience singleton collection that only include {@link #UNKNOWN}
     */
    public static final Collection<Role> UNKNOWN_SET = Set.of(UNKNOWN);
}
