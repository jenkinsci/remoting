/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.IOException;
import java.net.URL;
import org.jenkinsci.remoting.Role;
import org.jenkinsci.remoting.RoleChecker;

/**
 * {@link Callable} used to deliver a jar file to {@link RemoteClassLoader}.
 * @since TODO 2024-08
 */
final class PreloadJarTask2 implements DelegatingCallable<Boolean, IOException> {
    /**
     * Jar file to be preloaded.
     */
    private final URL[] jars;

    private final byte[][] contents;

    // TODO: This implementation exists starting from
    //  https://github.com/jenkinsci/remoting/commit/f3d0a81fdf46a10c3c6193faf252efaeaee98823
    //  Since this time nothing has blown up, but it still seems to be suspicious.
    //  The solution for null classloaders is available in RemoteDiagnostics.Script#call() in the Jenkins core codebase
    @CheckForNull
    private transient ClassLoader target = null;

    PreloadJarTask2(URL[] jars, byte[][] contents, @CheckForNull ClassLoader target) {
        if (jars.length != contents.length) {
            throw new IllegalArgumentException("Got " + jars.length + " jars and " + contents.length + " contents");
        }
        this.jars = jars;
        this.contents = contents;
        this.target = target;
    }

    @Override
    public ClassLoader getClassLoader() {
        return target;
    }

    @Override
    public Boolean call() throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        try {
            if (!(cl instanceof RemoteClassLoader)) {
                return false;
            }
            final RemoteClassLoader rcl = (RemoteClassLoader) cl;

            boolean r = false;
            for (int i = 0; i < jars.length; i++) {
                r |= rcl.prefetch(jars[i], contents[i]);
            }
            return r;
        } catch (IllegalAccessError iae) {
            // Catch the IAE instead of letting it be wrapped by remoting to suppress warnings logged on the agent-side
            throw new IOException(iae);
        }
    }

    /**
     * This task is only useful in the context that allows remote classloading, and by that point
     * any access control check is pointless. So just declare the worst possible role.
     */
    @Override
    public void checkRoles(RoleChecker checker) throws SecurityException {
        checker.check(this, Role.UNKNOWN);
    }

    private static final long serialVersionUID = -773448303394727271L;
}
