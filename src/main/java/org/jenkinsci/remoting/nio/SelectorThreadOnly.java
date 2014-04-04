package org.jenkinsci.remoting.nio;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.*;

/**
 * Marks the methods that can be only executed by the NIO selector thread.
 *
 * @author Kohsuke Kawaguchi
 */
@Target(METHOD)
@Retention(SOURCE)
@interface SelectorThreadOnly {
}
