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

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URL;

/**
 * {@link ObjectInputStream} that uses a specific {@link ClassLoader} and can use a {@link ClassFilter} to limit what classes can be loaded.
 */
public class ObjectInputStreamEx extends ObjectInputStream {
    private final ClassLoader cl;
    private final ClassFilter filter;

    /**
     * @deprecated as of 2.53
     */
    @Deprecated
    public ObjectInputStreamEx(InputStream in, ClassLoader cl) throws IOException {
        this(in, cl, ClassFilter.DEFAULT);
    }

    public ObjectInputStreamEx(InputStream in, ClassLoader cl, ClassFilter filter) throws IOException {
        super(in);
        this.cl = cl;
        this.filter = filter;

        // by default, the resolveObject is not called
        enableResolveObject(true);
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
        String name = desc.getName();
        try {
            return filter.check(Class.forName(filter.check(name), false, cl));
        } catch (ClassNotFoundException ex) {
            return super.resolveClass(desc);
        }
    }

    @Override
    protected Class<?> resolveProxyClass(String[] interfaces) throws IOException, ClassNotFoundException {
        ClassLoader latestLoader = cl;
        ClassLoader nonPublicLoader = null;
        boolean hasNonPublicInterface = false;

        // define proxy in class loader of non-public interface(s), if any
        Class<?>[] classObjs = new Class[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            Class<?> cl = Class.forName(interfaces[i], false, latestLoader);
            if ((cl.getModifiers() & Modifier.PUBLIC) == 0) {
                if (hasNonPublicInterface) {
                    if (nonPublicLoader != cl.getClassLoader()) {
                        throw new IllegalAccessError("conflicting non-public interface class loaders");
                    }
                } else {
                    nonPublicLoader = cl.getClassLoader();
                    hasNonPublicInterface = true;
                }
            }
            classObjs[i] = cl;
        }
        try {
            return Proxy.getProxyClass(hasNonPublicInterface ? nonPublicLoader : latestLoader, classObjs);
        } catch (IllegalArgumentException e) {
            throw new ClassNotFoundException(null, e);
        }
    }

    @Override
    protected Object resolveObject(Object obj) throws IOException {
        if (obj instanceof URL) {
            // SECURITY-637, URL deserialization could lead to DNS query
            return URLDeserializationHelper.wrapIfRequired((URL) obj);
        }
        return super.resolveObject(obj);
    }
}
