/*
 * The MIT License
 *
 * Copyright (c) 2011, CloudBees, Inc.
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

/**
 * When the other side wants to load a class from this side,
 * this hook is called to determine if we should refuse, give them one class file,
 * or a whole jar file.
 *
 * <p>
 * This mechanism is useful to selectively export classes to the remote node for protection,
 * as well as the performance optimization.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.12
 * @see Channel#setRemoteClassLoaderHook(RemoteClassLoaderHook) 
 */
public abstract class RemoteClassLoaderHook {
    public enum Mode {
        CLASS_FILE,
        JAR_FILE
    }

    /**
     * @param local
     *      The classloader with which the class name is resolved.
     * @param className
     *      The fully qualified class name being requested.
     * @return
     *      null to refuse the classloading, {@link Mode#CLASS_FILE} to send them a single class file,
     *      or {@link Mode#JAR_FILE} to send them a whole jar.
     */
    public abstract Mode loadClass(ClassLoader local, String className);

    /**
     * Always send one class file at a time.
     */
    public static final RemoteClassLoaderHook CLASS = new RemoteClassLoaderHook() {
        @Override
        public Mode loadClass(ClassLoader local, String className) {
            return Mode.CLASS_FILE;
        }
    };

    /**
     * Always try to send a whole jar file.
     */
    public static final RemoteClassLoaderHook JAR = new RemoteClassLoaderHook() {
        @Override
        public Mode loadClass(ClassLoader local, String className) {
            return Mode.JAR_FILE;
        }
    };

    /**
     * Default behaviour.
     *
     * Currently, this is to load one class at a time.
     */
    public static final RemoteClassLoaderHook DEFAULT = CLASS;
}
