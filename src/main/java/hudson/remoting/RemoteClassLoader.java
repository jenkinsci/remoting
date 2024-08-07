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
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.constant_pool_scanner.ConstantPoolScanner;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

/**
 * Loads class files from the other peer through {@link Channel}.
 *
 * <p>
 * If the {@link Channel#isRemoteClassLoadingAllowed() channel doesn't allow any remote classloading}, this classloader will be
 * created by will not attempt to load anything from the remote classloader. The reason we
 * create such a useless instance is so that when such classloader is sent back to the remote side again,
 * the remoting system can re-discover what {@link ClassLoader} this was tied to.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressFBWarnings(
        value = {"DMI_COLLECTION_OF_URLS", "DMI_BLOCKING_METHODS_ON_URL", "SIC_INNER_SHOULD_BE_STATIC_ANON"},
        justification =
                "Since this is based on the URLClassLoader, it is difficult to switch to URIs. We have no indication this causes noticeable resource issues. The implementations here and in URL reduce the impact.")
final class RemoteClassLoader extends URLClassLoader {

    private static final Logger LOGGER = Logger.getLogger(RemoteClassLoader.class.getName());

    interface Interruptible {
        void run() throws InterruptedException;
    }

    /**
     * Intercept {@link RemoteClassLoader#loadRemoteClass(String, Channel, ClassReference, RemoteClassLoader)} for unit tests.
     * See JENKINS-6604 and similar issues.
     * Should not be used for any other purpose.
     */
    static Interruptible TESTING_CLASS_LOAD;
    /**
     * Intercept {@link RemoteClassLoader#prefetchClassReference(String, Channel)} for unit tests.
     * Should not be used for any other purpose.
     */
    static Interruptible TESTING_CLASS_REFERENCE_LOAD;
    /**
     * Intercept {@link RemoteClassLoader#findResource(String)} for unit tests.
     * Should not be used for any other purpose.
     */
    static Interruptible TESTING_RESOURCE_LOAD;

    /**
     * The amount of time to sleep before retrying an interrupted class load.
     * This sleep keeps it from hammering the channel if there is a failure.
     * The default value is 100 (ms).
     */
    static int RETRY_SLEEP_DURATION_MILLISECONDS =
            Integer.getInteger(RemoteClassLoader.class.getName() + "retrySleepDurationMilliseconds", 100);
    /**
     * The total number of retries for an interrupted class load.
     * This makes the operation retry for an extended period of time but eventually timeout.
     * Combined with the default value for RETRY_SLEEP_DURATION_MILLISECONDS this gives a default
     * timeout of about 10 minutes, which is much less than the former (infinite) retry but still a significant
     * amount of time.
     * <p>
     * Setting this to zero keeps retrying forever.
     */
    static int MAX_RETRIES = Integer.getInteger(RemoteClassLoader.class.getName() + "maxRetries", 6000);

    /**
     * Proxy to the code running on remote end.
     * <p>
     * We use {@link DumbClassLoaderBridge} to ensure that all the methods
     * added post prefetch can be used on this object.
     */
    private final IClassLoader proxy;

    /**
     * HACK: if the {@link #proxy} object is a remoted object from the other side,
     * remember its proxy object so that we can map it back when this object is
     * exported to the other side.
     */
    private final Object underlyingProxy;

    /**
     * Remote peer that the {@link #proxy} is connected to.
     */
    private /*mostly final*/ Channel.Ref channel;

    private final Map<String, URLish> resourceMap = new HashMap<>();
    private final Map<String, Vector<URLish>> resourcesMap = new HashMap<>();

    /**
     * List of jars that are already pre-fetched through {@link #addURL(URL)}.
     *
     * <p>
     * Note that URLs in this set are URLs on the other peer.
     */
    private final Set<URL> prefetchedJars = new HashSet<>();

    /**
     * {@link ClassFile}s that were sent by remote as pre-fetch.
     */
    private final Map<String, ClassReference> prefetchedClasses = Collections.synchronizedMap(new HashMap<>());

    /**
     * Creates a remotable classloader
     *
     * @param parent Parent classloader. Can be {@code null} if there is no delegating classloader
     * @param proxy  Classloader proxy instance
     * @return Created classloader
     */
    @NonNull
    public static ClassLoader create(@CheckForNull ClassLoader parent, @NonNull IClassLoader proxy) {
        if (proxy instanceof ClassLoaderProxy) {
            // when the remote sends 'RemoteIClassLoader' as the proxy, on this side we get it
            // as ClassLoaderProxy. This means, the so-called remote classloader here is
            // actually our classloader that we exported to the other side.
            return ((ClassLoaderProxy) proxy).cl;
        }

        String name;
        try {
            name = proxy.getName();
        } catch (IOException ignored) {
            name = String.format(Locale.ROOT, "unknown-due-to-io-error %1$#x", System.identityHashCode(proxy));
        } catch (IllegalStateException ignored) {
            // IllegalStateException is thrown if the method does not exist on the remote side.
            // TODO remove this at some point in the future when Jenkins sets the minimum remoting version to
            // 3244.vf7f977e04755 or higher
            name = String.format(
                    Locale.ROOT,
                    "upgrade-remoting-to-3244.vf7f977e04755-or-higher %1$#x",
                    System.identityHashCode(proxy));
        }
        return new RemoteClassLoader(name, parent, proxy);
    }

    private RemoteClassLoader(String name, @CheckForNull ClassLoader parent, @NonNull IClassLoader proxy) {
        super(name, new URL[0], parent);
        final Channel channel = RemoteInvocationHandler.unwrap(proxy);
        this.channel = channel == null ? null : channel.ref();
        this.underlyingProxy = proxy;
        if (channel == null || !channel.remoteCapability.supportsPrefetch() || channel.getJarCache() == null) {
            proxy = new DumbClassLoaderBridge(proxy);
        }
        this.proxy = proxy;
    }

    /**
     * Returns the backing channel or {@code null} if the channel is disconnected or otherwise unavailable.
     *
     * @return the backing channel or {@code null}.
     * @since 2.52
     */
    @CheckForNull
    private Channel channel() {
        return this.channel == null ? null : this.channel.channel();
    }

    /**
     * If this {@link RemoteClassLoader} represents a classloader from the specified channel,
     * return its exported OID. Otherwise return -1.
     */
    /*package*/ int getOid(Channel channel) {
        return RemoteInvocationHandler.unwrap(underlyingProxy, channel);
    }

    /**
     * Finds and loads the class with the specified name from the URL search from the remote instance.
     * Any URLs referring to JAR files are loaded and opened as needed
     * until the class is found.
     *
     * @param name the name of the class
     * @return the resulting class
     * @throws ClassNotFoundException       if the class could not be found or if the loader is closed.
     * @throws UnsupportedClassVersionError The channel does not support the specified bytecode version
     * @throws ClassFormatError             Class format is incorrect
     * @throws LinkageError                 Linkage error during the class loading
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            // first attempt to load from locally fetched jars
            return super.findClass(name);
        } catch (ClassNotFoundException e) {
            final Channel channel = channel();
            if (channel == null || !channel.isRemoteClassLoadingAllowed()) {
                throw e;
            }
            // delegate to remote
            if (channel.remoteCapability.supportsMultiClassLoaderRPC()) {
                return loadWithMultiClassLoader(name, channel);
            } else {
                return fetchFromProxy(name, channel);
            }
        }
    }

    private Class<?> fetchFromProxy(String name, Channel channel) throws ClassNotFoundException {
        long startTime = System.nanoTime();
        byte[] bytes = proxy.fetch(name);
        channel.classLoadingTime.addAndGet(System.nanoTime() - startTime);
        channel.classLoadingCount.incrementAndGet();
        return loadClassFile(name, bytes);
    }

    private Class<?> loadWithMultiClassLoader(String name, Channel channel) throws ClassNotFoundException {
        /*
           In multi-classloader setup, RemoteClassLoaders do not retain the relationships among the original classloaders,
           so each RemoteClassLoader ends up loading classes on its own without delegating to other RemoteClassLoaders.

           See the classloader X/Y examples in HUDSON-5048 for the depiction of the problem.

           So instead, we find the right RemoteClassLoader to load the class on per class basis.
           The communication is optimized for the single classloader use, by always returning the class file image
           along with the reference to the initiating ClassLoader (if the initiating ClassLoader has already loaded this class,
           then the class file image is wasted.)
        */
        long startTime = System.nanoTime();
        ClassReference cr;
        if (channel.remoteCapability.supportsPrefetch()) {
            cr = prefetchClassReference(name, channel);
        } else {
            LOGGER.log(Level.FINER, "fetch2 on {0}", name);
            cr = new ClassReference(channel, proxy.fetch2(name));
        }
        channel.classLoadingTime.addAndGet(System.nanoTime() - startTime);
        channel.classLoadingCount.incrementAndGet();

        ClassLoader cl = cr.classLoader;
        if (cl instanceof RemoteClassLoader) {
            RemoteClassLoader rcl = (RemoteClassLoader) cl;
            return loadRemoteClass(name, channel, cr, rcl);
        } else {
            return cl.loadClass(name);
        }
    }

    private Class<?> loadRemoteClass(String name, Channel channel, ClassReference cr, RemoteClassLoader rcl)
            throws ClassNotFoundException {
        synchronized (rcl.getClassLoadingLock(name)) {
            Class<?> c = rcl.findLoadedClass(name);

            int tries = 0;
            while (true) {
                try {
                    invokeClassLoadTestingHookIfNeeded();

                    if (c != null) {
                        return c;
                    }

                    // TODO: check inner class handling
                    Future<byte[]> img = cr.classImage.resolve(channel, name.replace('.', '/') + ".class");
                    if (img.isDone()) {
                        try {
                            return rcl.loadClassFile(name, img.get());
                        } catch (ExecutionException x) {
                            // failure to retrieve a jar shouldn't fail the classloading
                        }
                    }

                    // if the load activity is still pending, or if the load had failed,
                    // fetch just this class file
                    return rcl.loadClassFile(name, proxy.fetch(name));
                } catch (IOException x) {
                    throw new ClassNotFoundException(name, x);
                } catch (InterruptedException | RemotingSystemException x) {
                    tries++;
                    if (shouldRetry(x, tries)) {
                        // pretend as if this operation is not interruptible.
                        // but we need to remember to set the interrupt flag back on
                        // before we leave this call.
                        sleepForRetry();
                        LOGGER.finer("Handling interrupt while loading remote class. Current retry count = " + tries
                                + ", maximum = " + MAX_RETRIES);
                        continue;
                    }
                    break;
                }
            }
            throw new ClassNotFoundException("Could not load class " + name + " after " + tries + " tries.");
        }
    }

    private void invokeClassLoadTestingHookIfNeeded() throws InterruptedException {
        // Testing support only.
        if (TESTING_CLASS_LOAD != null) {
            TESTING_CLASS_LOAD.run();
        }
    }

    private boolean shouldRetry(Throwable e, int tries) {
        return isRetryException(e) && hasMoreRetries(tries);
    }

    private boolean hasMoreRetries(int tries) {
        return MAX_RETRIES <= 0 || tries <= MAX_RETRIES;
    }

    private boolean isRetryException(Throwable e) {
        return e instanceof InterruptedException
                || (e instanceof RemotingSystemException
                        && (e.getCause() instanceof InterruptedException
                                || e.getCause() instanceof InterruptedIOException));
    }

    private ClassReference prefetchClassReference(String name, Channel channel) throws ClassNotFoundException {
        ClassReference cr;
        cr = prefetchedClasses.remove(name);
        if (cr == null) {
            LOGGER.log(Level.FINER, "fetch3({0})", name);

            int tries = 0;
            while (true) {
                try {
                    invokeClassReferenceLoadTestingHookIfNeeded();

                    Map<String, ClassFile2> all = proxy.fetch3(name);
                    synchronized (prefetchedClasses) {
                        /*
                         * Converts {@link ClassFile2} to {@link ClassReference} with minimal
                         * proxy creation. This creates a reference to {@link ClassLoader}, so
                         * it shouldn't be kept beyond the scope of single {@link #findClass(String)}  call.
                         */
                        class ClassReferenceBuilder {
                            private final Map<Integer, ClassLoader> classLoaders = new HashMap<>();

                            ClassReference toRef(ClassFile2 cf) {
                                int n = cf.classLoader;

                                ClassLoader cl = classLoaders.get(n);
                                if (cl == null) {
                                    classLoaders.put(n, cl = channel.importedClassLoaders.get(n));
                                }

                                return new ClassReference(cl, cf.image);
                            }
                        }
                        ClassReferenceBuilder crf = new ClassReferenceBuilder();

                        for (Map.Entry<String, ClassFile2> entry : all.entrySet()) {
                            String cn = entry.getKey();
                            ClassFile2 cf = entry.getValue();
                            ClassReference ref = crf.toRef(cf);

                            if (cn.equals(name)) {
                                cr = ref;
                            } else {
                                // where we remember the prefetch is sensitive to who references it,
                                // because classes need not be transitively visible in Java
                                if (cf.referer != null) {
                                    ref.rememberIn(cn, crf.toRef(cf.referer).classLoader);
                                } else {
                                    ref.rememberIn(cn, this);
                                }

                                LOGGER.log(Level.FINER, "prefetch {0} -> {1}", new Object[] {name, cn});
                            }

                            ref.rememberIn(cn, ref.classLoader);
                        }
                    }
                    break;
                } catch (InterruptedException | RemotingSystemException x) {
                    tries++;
                    if (shouldRetry(x, tries)) {
                        // pretend as if this operation is not interruptible.
                        // but we need to remember to set the interrupt flag back on
                        // before we leave this call.
                        sleepForRetry();
                        LOGGER.finer("Handling interrupt while fetching class reference. Current retry count = " + tries
                                + ", maximum = " + MAX_RETRIES);
                        continue;
                    }
                    throw determineRemotingSystemException(x);
                }

                // no code is allowed to reach here
            }

            assert cr != null;
        } else {
            LOGGER.log(Level.FINER, "findClass({0}) -> prefetch hit", name);
            channel.classLoadingPrefetchCacheCount.incrementAndGet();
        }
        return cr;
    }

    private void invokeClassReferenceLoadTestingHookIfNeeded() throws InterruptedException {
        // Testing support only.
        if (TESTING_CLASS_REFERENCE_LOAD != null) {
            TESTING_CLASS_REFERENCE_LOAD.run();
        }
    }

    /**
     * Loads class from the byte array.
     *
     * @param name  Name of the class
     * @param bytes Bytes
     * @return Loaded class
     * @throws UnsupportedClassVersionError The channel does not support the specified bytecode version
     * @throws ClassFormatError             Class format is incorrect
     * @throws LinkageError                 Linkage error during the class loading
     */
    private Class<?> loadClassFile(String name, byte[] bytes) throws LinkageError {
        if (bytes.length < 8) {
            throw new ClassFormatError(name + " is <8 bytes long");
        }
        short bytecodeLevel = (short) ((bytes[6] << 8) + (bytes[7] & 0xFF) - 44);
        final Channel channel = channel();
        if (channel != null && bytecodeLevel > channel.maximumBytecodeLevel) {
            throw new UnsupportedClassVersionError("this channel is restricted to JDK 1." + channel.maximumBytecodeLevel
                    + " compatibility but " + name + " was compiled for 1." + bytecodeLevel);
        }

        // if someone else is forcing us to load a class by giving as bytecode,
        // discard our prefetched version since we'll never use them.
        prefetchedClasses.remove(name);

        definePackage(name);

        // TODO: probably this wrapping is not required anymore
        try {
            return defineClass(name, bytes, 0, bytes.length);
        } catch (UnsupportedClassVersionError e) {
            throw (UnsupportedClassVersionError)
                    new UnsupportedClassVersionError("Failed to load " + name).initCause(e);
        } catch (ClassFormatError e) {
            throw (ClassFormatError) new ClassFormatError("Failed to load " + name).initCause(e);
        } catch (LinkageError e) {
            throw new LinkageError("Failed to load " + name, e);
        }
    }

    /**
     * Defining a package is necessary to make {@link Class#getPackage()} work,
     * which is often used to retrieve package-level annotations.
     * (for example, JAXB RI and Hadoop use them.)
     */
    private void definePackage(String name) {
        int idx = name.lastIndexOf('.');
        if (idx < 0) {
            return; // not in a package
        }

        String packageName = name.substring(0, idx);
        if (getPackage(packageName) != null) { // already defined
            return;
        }

        definePackage(packageName, null, null, null, null, null, null, null);
    }

    @Override
    @CheckForNull
    public URL findResource(String name) {
        // first attempt to load from locally fetched jars
        URL url = super.findResource(name);
        final Channel channel = channel();
        if (url != null || channel == null || !channel.isRemoteClassLoadingAllowed()) {
            return url;
        }

        int tries = 0;
        while (true) {
            try {
                if (resourceMap.containsKey(name)) {
                    URLish f = resourceMap.get(name);
                    if (f == null) {
                        return null; // no such resource
                    }
                    URL u = f.toURL();
                    if (u != null) {
                        return u;
                    }
                }

                invokeResourceLoadTestingHookIfNeeded();

                long startTime = System.nanoTime();

                ResourceFile r = proxy.getResource2(name);
                ResourceImageRef image = null;
                if (r != null) {
                    image = r.image;
                }

                channel.resourceLoadingTime.addAndGet(System.nanoTime() - startTime);
                channel.resourceLoadingCount.incrementAndGet();
                if (image == null) {
                    resourceMap.put(name, null);
                    return null;
                }

                URLish res = image.resolveURL(channel, name).get();
                resourceMap.put(name, res);
                return res.toURL();
            } catch (IOException | ExecutionException e) {
                throw new Error("Unable to load resource " + name, e);
            } catch (InterruptedException | RemotingSystemException x) {
                tries++;
                if (shouldRetry(x, tries)) {
                    // pretend as if this operation is not interruptible.
                    // but we need to remember to set the interrupt flag back on
                    // before we leave this call.
                    sleepForRetry();
                    LOGGER.finer("Handling interrupt while finding resource. Current retry count = " + tries
                            + ", maximum = " + MAX_RETRIES);
                    continue;
                }
                throw determineRemotingSystemException(x);
            }

            // no code is allowed to reach here
        }
    }

    private RemotingSystemException determineRemotingSystemException(Exception x) {
        return x instanceof RemotingSystemException ? (RemotingSystemException) x : new RemotingSystemException(x);
    }

    private void invokeResourceLoadTestingHookIfNeeded() throws InterruptedException {
        // Testing support only.
        if (TESTING_RESOURCE_LOAD != null) {
            TESTING_RESOURCE_LOAD.run();
        }
    }

    /**
     * @return {@code null} if one of the URLs cannot be converted.
     * E.g. when the referenced file does not exist.
     */
    @CheckForNull
    private static Vector<URL> toURLs(Vector<URLish> src) throws MalformedURLException {
        Vector<URL> r = new Vector<>(src.size());
        for (URLish s : src) {
            URL u = s.toURL();
            if (u == null) {
                return null; // abort
            }
            r.add(u);
        }
        return r;
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        final Channel channel = channel();
        if (channel == null || !channel.isRemoteClassLoadingAllowed()) {
            return EMPTY_ENUMERATION;
        }

        // TODO: use the locally fetched jars to speed up the look up
        // the challenge is how to combine the list from local jars
        // and the remote list

        int tries = 0;
        while (true) {
            try {
                Vector<URLish> v = resourcesMap.get(name);
                if (v != null) {
                    Vector<URL> urls = toURLs(v);
                    if (urls != null) {
                        return urls.elements();
                    }
                }

                invokeResourceLoadTestingHookIfNeeded();

                long startTime = System.nanoTime();
                ResourceFile[] images = proxy.getResources2(name);
                channel.resourceLoadingTime.addAndGet(System.nanoTime() - startTime);
                channel.resourceLoadingCount.incrementAndGet();

                v = new Vector<>();
                for (ResourceFile image : images) {
                    try {
                        // getResources2 always give us ResourceImageBoth so
                        // .get() shouldn't block
                        v.add(image.image.resolveURL(channel, name).get());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new Error("Failed to load resources " + name, e);
                    } catch (ExecutionException e) {
                        throw new Error("Failed to load resources " + name, e);
                    }
                }
                resourcesMap.put(name, v);

                Vector<URL> resURLs = toURLs(v);
                if (resURLs == null) {
                    // TODO: Better than NPE, but ideally needs correct error propagation from URLish
                    throw new IOException("One of the URLish objects cannot be converted to URL");
                }
                return resURLs.elements();
            } catch (InterruptedException | RemotingSystemException x) {
                tries++;
                if (shouldRetry(x, tries)) {
                    // pretend as if this operation is not interruptible.
                    // but we need to remember to set the interrupt flag back on
                    // before we leave this call.
                    sleepForRetry();
                    LOGGER.finer("Handling interrupt while finding resource. Current retry count = " + tries
                            + ", maximum = " + MAX_RETRIES);
                    continue;
                }
                throw determineRemotingSystemException(x);
            }

            // no code is allowed to reach here
        }
    }

    private void sleepForRetry() {
        try {
            if (RETRY_SLEEP_DURATION_MILLISECONDS > 0) {
                Thread.sleep(RETRY_SLEEP_DURATION_MILLISECONDS);
            }
        } catch (InterruptedException ignored) {
            // Not much to do if we can't sleep. Run through the tries more quickly.
        }
    }

    /**
     * @deprecated Use {@link Util#deleteDirectoryOnExit(File)}
     */
    @Deprecated
    public static void deleteDirectoryOnExit(File dir) {
        Util.deleteDirectoryOnExit(dir);
    }

    /**
     * Prefetches the specified jar with the specified content into this classloader.
     * @param jar Jar to be prefetched. Note that this file is an file on the other end,
     *            and doesn't point to anything meaningful locally.
     * @param content the jar content
     * @return true if the prefetch happened. false if the jar is already prefetched.
     * @see Channel#preloadJar(Callable, Class[])
     * @see hudson.remoting.PreloadJarTask2
     * @since TODO 2024-08
     */
    /*package*/ boolean prefetch(URL jar, byte[] content) throws IOException {
        synchronized (prefetchedJars) {
            if (prefetchedJars.contains(jar)) {
                return false;
            }

            String p = jar.getPath().replace('\\', '/');
            p = Util.getBaseName(p);
            File localJar = Util.makeResource(p, content);
            addURL(localJar.toURI().toURL());
            prefetchedJars.add(jar);
            return true;
        }
    }

    /**
     * Receiver-side of {@link ClassFile2} uses this to remember the prefetch information.
     */
    static class ClassReference {
        final ClassLoader classLoader;
        final ResourceImageRef classImage;

        ClassReference(Channel channel, ClassFile wireFormat) {
            this.classLoader = channel.importedClassLoaders.get(wireFormat.classLoader);
            this.classImage = new ResourceImageDirect(wireFormat.classImage);
        }

        ClassReference(ClassLoader classLoader, ResourceImageRef classImage) {
            this.classLoader = classLoader;
            this.classImage = classImage;
        }

        /**
         * Make the specified classloader remember this prefetch information.
         */
        void rememberIn(String className, ClassLoader cl) {
            if (cl instanceof RemoteClassLoader) {
                RemoteClassLoader rcl = (RemoteClassLoader) cl;
                rcl.prefetchedClasses.put(className, this);
            }
        }
    }

    /**
     * Wire format that we used to use for transferring a class file.
     * <p>
     * This is superseded by {@link ClassFile2} but left here for interop with legacy remoting jars.
     */
    public static class ClassFile implements Serializable {
        /**
         * oid of the classloader that should load this class.
         */
        final int classLoader;

        final byte[] classImage;

        ClassFile(int classLoader, byte[] classImage) {
            this.classLoader = classLoader;
            this.classImage = classImage;
        }

        private static final long serialVersionUID = 1L;

        public ClassFile2 upconvert(ClassFile2 referer, Class<?> clazz, URL local) {
            return new ClassFile2(classLoader, new ResourceImageDirect(classImage), referer, clazz, local);
        }
    }

    /**
     * Wire format that we use to transfer a resource file.
     * <p>
     * {@link Capability#supportsPrefetch()} enables this feature
     *
     * @since 2.24
     */
    public static class ResourceFile implements Serializable {
        /**
         * Encapsulates how to retrieve the actual resource.
         */
        final ResourceImageRef image;

        /**
         * While this object is still on the sender side,
         * this points to the location of the resource. Used by
         * the sender side to retrieve the resource when necessary.
         */
        @SuppressFBWarnings(
                value = "SE_TRANSIENT_FIELD_NOT_RESTORED",
                justification = "We're fine with the default null on the recipient side")
        final transient URL local;

        /**
         * Fall back for creating a direct resource
         */
        ResourceFile(URL local) throws IOException {
            this(new ResourceImageDirect(local), local);
        }

        ResourceFile(ResourceImageRef image, URL local) {
            this.image = image;
            this.local = local;
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * A class file as a subtype of {@link ResourceFile}.
     * This supersedes {@link ClassFile}.
     *
     * @since 2.24
     */
    public static class ClassFile2 extends ResourceFile {
        /**
         * oid of the classloader that should load this class.
         */
        final int classLoader;

        /**
         * When used with {@link IClassLoader#fetch3(String)},
         * this points to the class that was referencing this class.
         * <p>
         * This information is crucial in determining which classloaders are to cache
         * the prefetch information. Imagine classloader X requests fetch3("Foo"),
         * which returns 2 {@code ClassFile2} instances:
         *
         * <ol>
         *     <li>ClassFile2 #1: image of "Foo" is here and load this from classloader Y
         *     <li>ClassFile2 #2: image of "Bar" is here, referenced by "Foo", and load this from classloader Z
         * </ol>
         *
         * <p>
         * In this situation, we want to let classloader Y know that Bar is to be loaded from Z,
         * since that is the most likely classloader that will try to resolve Bar. In contrast,
         * remembering that in classloader X is only marginally useful.
         */
        final ClassFile2 referer;

        /**
         * While this object is still on the sender side, used to remember the actual
         * class that this {@link ClassFile2} represents.
         */
        @SuppressFBWarnings(
                value = "SE_TRANSIENT_FIELD_NOT_RESTORED",
                justification = "We're fine with the default null on the recipient side")
        final transient Class<?> clazz;

        ClassFile2(int classLoader, ResourceImageRef image, ClassFile2 referer, Class<?> clazz, URL local) {
            super(image, local);
            this.classLoader = classLoader;
            this.clazz = clazz;
            this.referer = referer;
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * Remoting interface.
     */
    public interface IClassLoader {
        /**
         * Retrieves the bytecode of a class.
         */
        byte[] fetch(String className) throws ClassNotFoundException;

        /**
         * Multi-classloader aware version of {@link #fetch(String)}.
         * In addition to the byte code, we get the reference to the classloader
         * that's supposed to load this.
         */
        ClassFile fetch2(String className) throws ClassNotFoundException;

        /**
         * Retrieve resource by name.
         *
         * @param name Name of the resource
         * @return Loaded resource. {@code null} if the resource is missing
         * @throws IOException Loading error
         */
        @CheckForNull
        byte[] getResource(String name) throws IOException;

        @NonNull
        byte[][] getResources(String name) throws IOException;

        // the rest is added as a part of Capability.supportsPrefetch()

        /**
         * {@link #fetch2(String)} plus pre-fetch.
         * <p>
         * The callee may return additional {@link ClassFile}s that it expects
         * to get loaded in a near future. This avoids repeated invocations of {@link #fetch2(String)}
         * thereby reducing the # of roundtrips.
         *
         * @see Capability#supportsPrefetch()
         * @since 2.PREFETCH
         * @since 2.24
         */
        Map<String, ClassFile2> fetch3(String className) throws ClassNotFoundException;

        /**
         * Remoting equivalent of {@link ClassLoader#getResource(String)}
         *
         * @return null if the resource is not found.
         * @since 2.24
         */
        @CheckForNull
        ResourceFile getResource2(String name) throws IOException;

        /**
         * Remoting equivalent of {@link ClassLoader#getResources(String)}
         *
         * @return never {@code null}
         * @since 2.24
         */
        @NonNull
        ResourceFile[] getResources2(String name) throws IOException;

        /**
         * Name of the classLoader
         * @since 3244.vf7f977e04755
         */
        String getName() throws IOException;
    }

    /**
     * Exports classloader over the channel.
     * <p>
     * If the classloader is an instance of {@link RemoteClassLoader}, this classloader will be unwrapped and reused.
     * Otherwise, a classloader object will be exported
     *
     * @param cl    Classloader to be exported
     * @param local Channel
     * @return Exported reference. This reference is always {@link Serializable} though interface is not explicit about that
     */
    public static IClassLoader export(@NonNull ClassLoader cl, @NonNull Channel local) {
        if (cl instanceof RemoteClassLoader) {
            // check if this is a remote classloader from the channel
            final RemoteClassLoader rcl = (RemoteClassLoader) cl;
            int oid = RemoteInvocationHandler.unwrap(rcl.underlyingProxy, local);
            if (oid != -1) {
                return new RemoteIClassLoader(oid, rcl.proxy);
            }
        }
        // Remote classloader operates in the System scope (JENKINS-45294).
        // It's probably YOLO, but otherwise the termination calls may be unable
        // to execute correctly.
        return local.export(IClassLoader.class, new ClassLoaderProxy(cl, local), false, false, false);
    }

    public static void pin(ClassLoader cl, Channel local) {
        if (cl instanceof RemoteClassLoader) {
            // check if this is a remote classloader from the channel
            final RemoteClassLoader rcl = (RemoteClassLoader) cl;
            int oid = RemoteInvocationHandler.unwrap(rcl.proxy, local);
            if (oid != -1) {
                return;
            }
        }
        local.pin(new ClassLoaderProxy(cl, local));
    }

    /**
     * Exports and just returns the object ID, instead of obtaining the proxy.
     */
    static int exportId(ClassLoader cl, Channel local) {
        return local.internalExport(IClassLoader.class, new ClassLoaderProxy(cl, local), false);
    }

    /*package*/ static final class ClassLoaderProxy implements IClassLoader {
        final ClassLoader cl;
        final Channel channel;
        /**
         * Class names that we've already sent to the other side as pre-fetch.
         */
        private final Set<String> prefetched = new HashSet<>();

        public ClassLoaderProxy(@NonNull ClassLoader cl, Channel channel) {
            this.cl = cl;
            this.channel = channel;
        }

        @Override
        public byte[] fetch(String className) throws ClassNotFoundException {
            if (!USE_BOOTSTRAP_CLASSLOADER && cl == PSEUDO_BOOTSTRAP) {
                throw new ClassNotFoundException("Classloading from bootstrap classloader disabled");
            }

            InputStream in = cl.getResourceAsStream(className.replace('.', '/') + ".class");
            if (in == null) {
                throw new ClassNotFoundException(className);
            }

            try {
                return Util.readFully(in);
            } catch (IOException e) {
                throw new ClassNotFoundException();
            }
        }

        @Override
        public ClassFile fetch2(String className) throws ClassNotFoundException {
            ClassLoader ecl = cl.loadClass(className).getClassLoader();
            if (ecl == null) {
                if (USE_BOOTSTRAP_CLASSLOADER) {
                    ecl = PSEUDO_BOOTSTRAP;
                } else {
                    throw new ClassNotFoundException("Classloading from system classloader disabled");
                }
            }

            try {
                InputStream in = ecl.getResourceAsStream(className.replace('.', '/') + ".class");
                if (in == null) {
                    throw new ClassNotFoundException(className + " (" + ecl + " did not find class file)");
                }
                return new ClassFile(exportId(ecl, channel), Util.readFully(in));
            } catch (IOException e) {
                throw new ClassNotFoundException();
            }
        }

        /**
         * Fetch a single class and creates a {@link ClassFile2} for it.
         */
        public ClassFile2 fetch4(String className, @CheckForNull ClassFile2 referer) throws ClassNotFoundException {
            Class<?> referrerClass = referer == null ? null : referer.clazz;
            Class<?> c;
            try {
                c = (referer == null ? this.cl : referer.clazz.getClassLoader()).loadClass(className);
            } catch (LinkageError e) {
                throw new LinkageError("Failed to load " + className + " via " + referrerClass, e);
            }
            ClassLoader ecl = c.getClassLoader();
            if (ecl == null) {
                if (USE_BOOTSTRAP_CLASSLOADER) {
                    ecl = PSEUDO_BOOTSTRAP;
                } else {
                    throw new ClassNotFoundException(
                            "Bootstrap pseudo-classloader disabled: " + className + " via " + referrerClass);
                }
            }

            try {
                final URL urlOfClassFile = Which.classFileUrl(c);

                try {
                    File jar = Which.jarFile(c);
                    if (jar.isFile()) { // for historical reasons the jarFile method can return a directory
                        Checksum sum = channel.jarLoader.calcChecksum(jar);

                        ResourceImageRef imageRef;
                        if (referer == null && !channel.jarLoader.isPresentOnRemote(sum)) {
                            // for the class being requested, if the remote doesn't have the jar yet
                            // send the image as well, so as not to require another call to get this class loaded
                            imageRef = new ResourceImageBoth(urlOfClassFile, sum);
                        } else { // otherwise just send the checksum and save space
                            imageRef = new ResourceImageInJar(
                                    sum,
                                    null /* TODO: we need to check if the URL of c points to the expected location of the file */);
                        }

                        return new ClassFile2(exportId(ecl, channel), imageRef, referer, c, urlOfClassFile);
                    }
                } catch (IllegalArgumentException e) {
                    // we determined that 'c' isn't in a jar file
                    LOGGER.log(Level.FINE, c + " isn't in a jar file: " + urlOfClassFile, e);
                }
                return fetch2(className).upconvert(referer, c, urlOfClassFile);
            } catch (IOException e) {
                throw new ClassNotFoundException("Failed to load " + className + " via " + referrerClass, e);
            }
        }

        @Override
        @SuppressFBWarnings(
                value = "URLCONNECTION_SSRF_FD",
                justification = "This is only used for managing the jar cache as files.")
        public Map<String, ClassFile2> fetch3(String className) throws ClassNotFoundException {
            ClassFile2 cf = fetch4(className, null);
            Map<String, ClassFile2> all = new HashMap<>();
            all.put(className, cf);
            synchronized (prefetched) {
                prefetched.add(className);
            }
            try {
                for (String other : analyze(cf.local.openStream())) {
                    synchronized (prefetched) {
                        if (!prefetched.add(other)) {
                            continue;
                        }
                    }
                    try {
                        // TODO could even traverse second-level dependencies, etc.
                        all.put(other, fetch4(other, cf));
                    } catch (ClassNotFoundException x) {
                        // ignore: might not be real class name, etc.
                    } catch (LinkageError x) {
                        // maybe this class won't be actually used.
                        // in any case, this shouldn't cause the loading of the current class to fail
                    }
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to analyze the class file: " + cf.local, e);
                // ignore
            }
            return all;
        }

        @CheckForNull
        private URL getResourceURL(String name) {
            URL resource = cl.getResource(name);
            if (resource == null) {
                return null;
            }

            if (!USE_BOOTSTRAP_CLASSLOADER) {
                URL systemResource = PSEUDO_BOOTSTRAP.getResource(name);
                if (resource.equals(systemResource)) {
                    return null;
                }
            }

            return resource;
        }

        @Override
        @CheckForNull
        public ResourceFile getResource2(String name) throws IOException {
            URL resource = getResourceURL(name);
            if (resource == null) {
                return null;
            }

            return makeResource(name, resource);
        }

        private ResourceFile makeResource(String name, URL resource) throws IOException {
            try {
                File jar = Which.jarFile(resource, name);
                if (jar.isFile()) { // for historical reasons the jarFile method can return a directory
                    Checksum sum = channel.jarLoader.calcChecksum(jar);
                    ResourceImageRef ir;
                    if (!channel.jarLoader.isPresentOnRemote(sum)) {
                        ir = new ResourceImageBoth(resource, sum); // remote probably doesn't have
                    } else {
                        ir = new ResourceImageInJar(sum, null);
                    }
                    return new ResourceFile(ir, resource);
                }
            } catch (IllegalArgumentException e) {
                LOGGER.log(Level.FINE, name + " isn't in a jar file: " + resource, e);
            }
            return new ResourceFile(resource);
        }

        @Override
        @SuppressFBWarnings(
                value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS",
                justification = "Null return value is a part of the public interface")
        @CheckForNull
        public byte[] getResource(String name) throws IOException {
            URL resource = getResourceURL(name);
            if (resource == null) {
                return null;
            }
            return Util.readFully(resource.openStream());
        }

        public List<URL> getResourcesURL(String name) throws IOException {
            List<URL> images = new ArrayList<>();

            Set<URL> systemResources = null;
            if (!USE_BOOTSTRAP_CLASSLOADER) {
                systemResources = new HashSet<>();
                Enumeration<URL> e = PSEUDO_BOOTSTRAP.getResources(name);
                while (e.hasMoreElements()) {
                    systemResources.add(e.nextElement());
                }
            }

            Enumeration<URL> e = cl.getResources(name);
            while (e.hasMoreElements()) {
                URL url = e.nextElement();
                if (systemResources == null || !systemResources.contains(url)) {
                    images.add(url);
                }
            }

            return images;
        }

        @Override
        @NonNull
        public byte[][] getResources(String name) throws IOException {
            List<URL> x = getResourcesURL(name);
            byte[][] r = new byte[x.size()][];
            for (int i = 0; i < r.length; i++) {
                r[i] = Util.readFully(x.get(i).openStream());
            }
            return r;
        }

        @Override
        @NonNull
        public ResourceFile[] getResources2(String name) throws IOException {
            List<URL> x = getResourcesURL(name);
            ResourceFile[] r = new ResourceFile[x.size()];
            for (int i = 0; i < r.length; i++) {
                r[i] = makeResource(name, x.get(i));
            }
            return r;
        }

        @Override
        public String getName() throws IOException {
            return cl.getName();
        }

        @Override
        public boolean equals(Object that) {
            if (this == that) {
                return true;
            }
            if (that == null || getClass() != that.getClass()) {
                return false;
            }

            return cl.equals(((ClassLoaderProxy) that).cl);
        }

        @Override
        public int hashCode() {
            return cl.hashCode();
        }

        @Override
        public String toString() {
            return super.toString() + '[' + cl + ']';
        }

        /**
         * Since bootstrap classloader by itself doesn't have the {@link ClassLoader} object
         * representing it (a crazy design, really), accessing it is unnecessarily hard.
         *
         * <p>
         * So we create a child classloader that delegates directly to the bootstrap, without adding
         * any new classpath. In this way, we can effectively use this classloader as a representation
         * of the bootstrap classloader.
         */
        private static final ClassLoader PSEUDO_BOOTSTRAP = new URLClassLoader(new URL[0], null) {
            @Override
            public String toString() {
                return "PSEUDO_BOOTSTRAP";
            }
        };
    }

    /**
     * {@link IClassLoader} to be shipped back to the channel where it came from.
     *
     * <p>
     * When the object stays on the side where it's created, delegate to the proxy field
     * to work (which will be the remote instance.) Once transferred to the other side,
     * resolve back to the instance on the server.
     */
    private static class RemoteIClassLoader implements IClassLoader, SerializableOnlyOverRemoting {
        private final transient IClassLoader proxy;
        private final int oid;

        private RemoteIClassLoader(int oid, IClassLoader proxy) {
            this.proxy = proxy;
            this.oid = oid;
        }

        @Override
        public byte[] fetch(String className) throws ClassNotFoundException {
            return proxy.fetch(className);
        }

        @Override
        public ClassFile fetch2(String className) throws ClassNotFoundException {
            return proxy.fetch2(className);
        }

        @Override
        public Map<String, ClassFile2> fetch3(String className) throws ClassNotFoundException {
            return proxy.fetch3(className);
        }

        @Override
        public byte[] getResource(String name) throws IOException {
            return proxy.getResource(name);
        }

        @Override
        @NonNull
        public byte[][] getResources(String name) throws IOException {
            return proxy.getResources(name);
        }

        @Override
        public ResourceFile getResource2(String name) throws IOException {
            return proxy.getResource2(name);
        }

        @Override
        @NonNull
        public ResourceFile[] getResources2(String name) throws IOException {
            return proxy.getResources2(name);
        }

        @Override
        public String getName() throws IOException {
            return proxy.getName();
        }

        private Object readResolve() throws ObjectStreamException {
            try {
                return getChannelForSerialization().getExportedObject(oid);
            } catch (ExecutionException ex) {
                return new BrokenIClassLoader(ex);
            }
        }

        private static final long serialVersionUID = 1L;
    }

    private static final class BrokenIClassLoader implements IClassLoader, SerializableOnlyOverRemoting {

        private static final long serialVersionUID = 1L;
        private final ExecutionException cause;

        BrokenIClassLoader(ExecutionException cause) {
            this.cause = cause;
        }

        @Override
        public byte[] fetch(String className) throws ClassNotFoundException {
            throw new ClassNotFoundException(className, cause);
        }

        @Override
        public ClassFile fetch2(String className) throws ClassNotFoundException {
            throw new ClassNotFoundException(className, cause);
        }

        @Override
        public Map<String, ClassFile2> fetch3(String className) throws ClassNotFoundException {
            throw new ClassNotFoundException(className, cause);
        }

        @Override
        public byte[] getResource(String name) throws IOException {
            throw new IOException("Cannot get " + name, cause);
        }

        @Override
        public byte[][] getResources(String name) throws IOException {
            throw new IOException("Cannot get " + name, cause);
        }

        @Override
        public ResourceFile getResource2(String name) throws IOException {
            throw new IOException("Cannot get " + name, cause);
        }

        @Override
        public ResourceFile[] getResources2(String name) throws IOException {
            throw new IOException("Cannot get " + name, cause);
        }

        @Override
        public String getName() throws IOException {
            throw new IOException("Cannot getName", cause);
        }
    }

    private static Iterable<String> analyze(InputStream bytecode) {
        // Other options include ASM and org.apache.tools.ant.taskdefs.optional.depend.constantpool.ConstantPool.
        try {
            return ConstantPoolScanner.dependencies(bytecode);
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, "could not parse bytecode", x);
            return Collections.emptySet();
        }
    }

    /**
     * If set to true, classes loaded by the bootstrap classloader will be also remoted to the remote JVM.
     * By default, classes that belong to the bootstrap classloader will NOT be remoted, as each JVM gets its own JRE
     * and their versions can be potentially different.
     */
    public static boolean USE_BOOTSTRAP_CLASSLOADER =
            Boolean.getBoolean(RemoteClassLoader.class.getName() + ".useBootstrapClassLoader");

    private static final Enumeration<URL> EMPTY_ENUMERATION = new Vector<URL>().elements();
}
