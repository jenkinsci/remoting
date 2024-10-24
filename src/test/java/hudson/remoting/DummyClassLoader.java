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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.io.IOUtils;

/**
 * Used to load a dummy class
 * out of nowhere, to test {@link RemoteClassLoader} by creating a class
 * that only exists on one side of the channel but not the other.
 *
 * <p>
 * Given a class in a "remoting" package, this classloader is capable of loading the same version of the class
 * in the "rem0ting" package.
 *
 * @author Kohsuke Kawaguchi
 */
class DummyClassLoader extends ClassLoader {

    class Entry {
        final String physicalName;
        final String logicalName;
        final String physicalPath;
        final String logicalPath;
        final Class<?> c;

        Entry(Class<?> c) {
            this.c = c;
            physicalName = c.getName();
            assert physicalName.contains("remoting.Test");
            logicalName = physicalName.replace("remoting", "rem0ting");
            physicalPath = physicalName.replace('.', '/') + ".class";
            logicalPath = logicalName.replace('.', '/') + ".class";
        }

        private byte[] loadTransformedClassImage() throws IOException {
            InputStream in = getResourceAsStream(physicalPath);
            String data = IOUtils.toString(in, StandardCharsets.ISO_8859_1);
            // Single-character substitutions will not change length fields in bytecode etc.
            String data2 = data.replaceAll("remoting(.)Test", "rem0ting$1Test");
            return data2.getBytes(StandardCharsets.ISO_8859_1);
        }

        @Override
        public String toString() {
            return physicalName;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public DummyClassLoader(Class<?>... classes) {
        this(DummyClassLoader.class.getClassLoader(), classes);
    }

    public DummyClassLoader(ClassLoader parent, Class<?>... classes) {
        super(parent);
        assert classes.length != 0;
        for (Class<?> c : classes) {
            entries.add(new Entry(c));
        }
    }

    /**
     * Short cut to create an instance of a transformed class.
     */
    public static Object apply(Class<?> c) {
        return new DummyClassLoader(c).load(c);
    }

    /**
     * Loads a class that looks like an exact clone of the named class under
     * a different class name.
     */
    public Object load(Class<?> c) {
        for (Entry e : entries) {
            if (e.c == c) {
                try {
                    return loadClass(e.logicalName).getConstructor().newInstance();
                } catch (InstantiationException
                        | IllegalAccessException
                        | ClassNotFoundException
                        | NoSuchMethodException
                        | InvocationTargetException x) {
                    throw new Error(x);
                }
            }
        }
        throw new IllegalStateException();
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        for (Entry e : entries) {
            if (name.equals(e.logicalName)) {
                // rename a class
                try {
                    byte[] bytes = e.loadTransformedClassImage();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException x) {
                    throw new ClassNotFoundException("Bytecode manipulation failed", x);
                }
            }
        }

        return super.findClass(name);
    }

    @Override
    protected URL findResource(String name) {
        for (Entry e : entries) {
            if (name.equals(e.logicalPath)) {
                try {
                    File f = File.createTempFile("rmiTest", "class");
                    try (OutputStream os = new FileOutputStream(f)) {
                        os.write(e.loadTransformedClassImage());
                    }
                    f.deleteOnExit();
                    return f.toURI().toURL();
                } catch (IOException x) {
                    return null;
                }
            }
        }
        return super.findResource(name);
    }

    @Override
    public String toString() {
        return super.toString() + "[" + entries + "]";
    }
}
