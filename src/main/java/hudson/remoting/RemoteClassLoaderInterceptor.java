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

import java.io.File;
import java.net.URL;

/**
 * This class can receive notification and control the remote classloading operation before/after
 * it happens.
 * 
 * @author Kohsuke Kawaguchi
 */
public abstract class RemoteClassLoaderInterceptor {
    public enum Mode {
        RETRY_LOCAL,
        REMOTE,
        FAIL
    }

    /**
     * This method is called after a local resolution of a class is attempted once.
     *
     * @param local
     *      The classloader that's considering how to resolve resources.
     * @param className
     *      The fully qualified class name being requested.
     * @return
     *      {@link Mode#RETRY_LOCAL} to try resolving resources locally again.
     *      This is useful when this interceptor has locally made the class available
     *      via {@link RemoteClassLoader#addURL(URL)} or some other means.
     *
     *      {@link Mode#REMOTE} will have the remoting library request a class from
     *      the other side.
     *
     *      {@link Mode#FAIL} will have the request fail without consulting to the other side
     *      of the channel. This is a potentially useful performance optimization if
     *      the interceptor somehow knows that the resolution will fail, as we can save
     *      a network round trip.
     */
    public Mode findClass(RemoteClassLoader local, String className) throws ClassNotFoundException {
        return local.getChannel().isRestricted() ? Mode.FAIL : Mode.REMOTE;
    }

    /**
     * {@link ClassLoader#findResource(String)} version of {@link #findClass(RemoteClassLoader, String)}
     */
    public Mode findResource(RemoteClassLoader local, String name) {
        return local.getChannel().isRestricted() ? Mode.FAIL : Mode.REMOTE;
    }

    public Mode findResources(RemoteClassLoader local, String name) {
        return local.getChannel().isRestricted() ? Mode.FAIL : Mode.REMOTE;
    }

    /**
     * Called right when we are about to load a class file obtained from the other side of the channel.
     *
     * <p>
     * This can be useful for two purposes; one is to locally cache the class file for future
     * use (in conjunction with your {@link #findClass(RemoteClassLoader, String) that implements on-demand local class addition}),
     * and the other is to test the integrity of the class file image.
     *
     * <p>
     * Return normally to have the classloading proceed as usual, or exit abnormally to have the classloading fail.
     *
     * @param cl
     *      ClassLoader that's about to load this class file
     * @param className
     *      Fully qualified class name.
     * @param classFileImage
     *      Binary image of a class file.
     */
    public void verifyClassFile(RemoteClassLoader cl, String className, byte[] classFileImage) throws ClassNotFoundException {}

    /**
     * Called right when we are about to load a jar file obtained from the other side of the channel.
     *
     * See {@link #verifyClassFile(RemoteClassLoader, String, byte[])} for the purpose. This version
     * is used when the remote sends us a whole jar file instead of just one class.
     *
     * <p>
     * Return normally to have the classloading proceed as usual, or exit abnormally to have the classloading fail.
     *
     * @param cl
     *      ClassLoader that's about to load this class file
     * @param className
     *      Fully qualified class name.
     * @param jar
     *      Jar file obtained from the other side.
     */
    public void verifyJarFile(RemoteClassLoader cl, String className, File jar) throws ClassNotFoundException {}



    public static final RemoteClassLoaderInterceptor DEFAULT = new RemoteClassLoaderInterceptor() {};
}
