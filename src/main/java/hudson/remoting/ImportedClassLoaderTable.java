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

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Imported {@link ClassLoader} table.
 * Stores references to {@link ClassLoader} instances, which have been exported to the instance by the remote side.
 * @author Kohsuke Kawaguchi
 * @since 2.0
 */
final class ImportedClassLoaderTable {
    final Channel channel;
    final Map<RemoteClassLoader.IClassLoader, ClassLoader> classLoaders = new ConcurrentHashMap<>();

    ImportedClassLoaderTable(Channel channel) {
        this.channel = channel;
    }

    /**
     * Maps the exported object ID to the classloader.
     *
     * <p>
     * This method "consumes" the given oid for the purpose of reference counting.
     */
    @NonNull
    public ClassLoader get(int oid) {
        return get(RemoteInvocationHandler.wrap(
                channel, oid, RemoteClassLoader.IClassLoader.class, false, false, false, false));
    }

    /**
     * Retrieves classloader for the specified proxy class.
     * If the classloader instance is missing in the cache, it will be created during the call.
     * @param classLoaderProxy Proxy instance
     * @return Classloader instance
     */
    @NonNull
    public ClassLoader get(@NonNull RemoteClassLoader.IClassLoader classLoaderProxy) {
        // we need to be able to use the same hudson.remoting classes, hence delegate to this class loader.
        return classLoaders.computeIfAbsent(
                classLoaderProxy, proxy -> RemoteClassLoader.create(channel.baseClassLoader, proxy));
    }
}
