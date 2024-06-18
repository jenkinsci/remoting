/*
 * The MIT License
 *
 * Copyright (c) 2021, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting;

import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * For remoting internal use only: {@link Callable}s implement
 * this marker interface to be allowed to bypass the required role check of
 * {@link RequiredRoleCheckerWrapper}, as the application-defined
 * roles are unknown to remoting.
 * Callables defined in Jenkins need to extend {@code MasterToSlaveCallable} or
 * (rarely) {@code SlaveToMasterCallable}, or (almost never) implement
 * their own role checks.
 *
 * @since TODO
 */
@Restricted(NoExternalUse.class)
/* package */ interface InternalCallable<V, T extends Throwable> extends Callable<V, T> {
    @Override
    default void checkRoles(RoleChecker checker) throws SecurityException {
        // no-op
    }
}
