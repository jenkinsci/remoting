package org.jenkinsci.remoting.nio;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.*;

/**
 * Marks the methods that can be only executed by the NIO selector thread.
 *
 * <h3>Rules</h3>
 * <ul>
 *     <li>If the base method has this annotation, all the overriding methods must have this.
 *     <li>Only the caller that's marked as {@link SelectorThreadOnly} can call these methods.
 * </ul>
 *
 * @author Kohsuke Kawaguchi
 */
@Target(METHOD)
@Retention(SOURCE)
@interface SelectorThreadOnly {
}
