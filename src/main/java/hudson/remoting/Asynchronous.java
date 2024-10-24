package hudson.remoting;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Used on a method in a remotable exported interface to designate
 * that the call is made asynchronously. The call will be issued,
 * but the caller will return without waiting for the return value
 * to come back from the other side.
 *
 * The signature of the method must return void.
 *
 * <pre>
 * interface Foo {
 *     void bar();
 *     &#64;Asynchronous
 *     void zot();
 * }
 *
 * Foo foo = getSomeRemoteReferenceToFoo();
 * // this invocation calls a remote method, wait for that to complete,
 * // then return.
 * foo.bar();
 * // this invocation returns immediately after the request to execute a remote method
 * // is sent to the other side. There's no ordering guarantee as to when
 * // this method actually gets executed. For example, if you invoke two async
 * // calls, they may execute in the reverse order.
 * foo.zot();
 * </pre>
 *
 * @see Channel#callAsync(Callable)
 * @author Kohsuke Kawaguchi
 * @since 2.24
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Asynchronous {}
