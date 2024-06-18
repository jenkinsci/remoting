package org.jenkinsci.remoting.nio;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

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
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
@interface SelectorThreadOnly {}
