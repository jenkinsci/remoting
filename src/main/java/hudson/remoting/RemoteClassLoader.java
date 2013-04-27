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
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.MalformedURLException;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.constant_pool_scanner.ConstantPoolScanner;

import static hudson.remoting.Util.*;
import static java.util.logging.Level.*;

/**
 * Loads class files from the other peer through {@link Channel}.
 *
 * <p>
 * If the {@linkplain Channel#isRestricted() channel is restricted}, this classloader will be
 * created by will not attempt to load anything from the remote classloader. The reason we
 * create such a useless instance is so that when such classloader is sent back to the remote side again,
 * the remoting system can re-discover what {@link ClassLoader} this was tied to.
 *
 * @author Kohsuke Kawaguchi
 */
final class RemoteClassLoader extends URLClassLoader {

    private static final Logger LOGGER = Logger.getLogger(RemoteClassLoader.class.getName());

    /**
     * Proxy to the code running on remote end.
     *
     * We use {@link DumbClassLoaderBridge} to ensure that all the methods
     * added post prefetch can be used on this object.
     */
    private final IClassLoader proxy;
    /**
     * Remote peer that the {@link #proxy} is connected to.
     */
    private final Channel channel;

    private final Map<String,URLish> resourceMap = new HashMap<String,URLish>();
    private final Map<String,Vector<URLish>> resourcesMap = new HashMap<String,Vector<URLish>>();

    /**
     * List of jars that are already pre-fetched through {@link #addURL(URL)}.
     *
     * <p>
     * Note that URLs in this set are URLs on the other peer.
     */
    private final Set<URL> prefetchedJars = new HashSet<URL>();

    /**
     * {@link ClassFile}s that were sent by remote as pre-fetch.
     */
    private final Map<String,ClassReference> prefetchedClasses = Collections.synchronizedMap(new HashMap<String,ClassReference>());

    public static ClassLoader create(ClassLoader parent, IClassLoader proxy) {
        if(proxy instanceof ClassLoaderProxy) {
            // when the remote sends 'RemoteIClassLoader' as the proxy, on this side we get it
            // as ClassLoaderProxy. This means, the so-called remote classloader here is
            // actually our classloader that we exported to the other side.
            return ((ClassLoaderProxy)proxy).cl;
        }
        return new RemoteClassLoader(parent, proxy);
    }

    private RemoteClassLoader(ClassLoader parent, IClassLoader proxy) {
        super(new URL[0],parent);
        this.channel = RemoteInvocationHandler.unwrap(proxy);
        if (!channel.remoteCapability.supportsPrefetch() || channel.getJarCache()==null)
            proxy = new DumbClassLoaderBridge(proxy);
        this.proxy = proxy;
    }

    /**
     * If this {@link RemoteClassLoader} represents a classloader from the specified channel,
     * return its exported OID. Otherwise return -1.
     */
    /*package*/ int getOid(Channel channel) {
        return RemoteInvocationHandler.unwrap(proxy,channel);
    }

    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            // first attempt to load from locally fetched jars
            return super.findClass(name);
        } catch (ClassNotFoundException e) {
            if(channel.isRestricted())
                throw e;
            // delegate to remote
            if (channel.remoteCapability.supportsMultiClassLoaderRPC()) {
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
                ClassReference cf;
                if (channel.remoteCapability.supportsPrefetch()) {
                    cf = prefetchedClasses.remove(name);
                    if (cf == null) {
                        Map<String,ClassFile2> all = proxy.fetch3(name);
                        synchronized (prefetchedClasses) {
                            for (Map.Entry<String,ClassFile2> entry : all.entrySet()) {
                                ClassReference ref = new ClassReference(channel,entry.getValue());
                                if (entry.getKey().equals(name)) {
                                    cf = ref;
                                    LOGGER.log(Level.FINER, "fetch3 on {0}", name);
                                } else {
                                    prefetchedClasses.put(entry.getKey(), ref);
                                    LOGGER.log(Level.FINER, "prefetch {0} -> {1}", new Object[] {name, entry.getKey()});
                                }
                                if (ref.classLoader instanceof RemoteClassLoader) {
                                    RemoteClassLoader rcl = (RemoteClassLoader) ref.classLoader;
                                    rcl.prefetchedClasses.put(entry.getKey(), ref);
                                }
                            }
                        }

                        assert cf != null;
                    } else {
                        LOGGER.log(Level.FINER, "had already fetched {0}", name);
                    }
                } else {
                    LOGGER.log(Level.FINER, "fetch2 on {0}", name);
                    cf = new ClassReference(channel,proxy.fetch2(name));
                }
                channel.classLoadingTime.addAndGet(System.nanoTime()-startTime);
                channel.classLoadingCount.incrementAndGet();

                ClassLoader cl = cf.classLoader;
                if (cl instanceof RemoteClassLoader) {
                    RemoteClassLoader rcl = (RemoteClassLoader) cl;
                    synchronized (_getClassLoadingLock(rcl, name)) {
                        Class<?> c = rcl.findLoadedClass(name);
                        if (TESTING) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException x) {
                                assert false : x;
                            }
                        }
                        if (c==null) {
                            // TODO: check inner class handling
                            try {
                                c = rcl.loadClassFile(name,cf.classImage.resolve(channel,name.replace('.','/')+".class"));
                            } catch (IOException x) {
                                throw new ClassNotFoundException(name,x);
                            } catch (InterruptedException x) {
                                throw new ClassNotFoundException(name,x);
                            }
                        }
                        return c;
                    }
                } else {
                    return cl.loadClass(name);
                }
            } else {
                long startTime = System.nanoTime();
                byte[] bytes = proxy.fetch(name);
                channel.classLoadingTime.addAndGet(System.nanoTime()-startTime);
                channel.classLoadingCount.incrementAndGet();

                return loadClassFile(name, bytes);
            }
        }
    }

    private static Method gCLL;
    static {
        try {
            gCLL = ClassLoader.class.getDeclaredMethod("getClassLoadingLock", String.class);
            gCLL.setAccessible(true);
        } catch (NoSuchMethodException x) {
            // OK, Java 6
        } catch (Exception x) {
            LOGGER.log(WARNING, null, x);
        }
    }
    private static Object _getClassLoadingLock(RemoteClassLoader rcl, String name) {
        // Java 7: return rcl.getClassLoadingLock(name);
        if (gCLL != null) {
            try {
                return gCLL.invoke(rcl, name);
            } catch (Exception x) {
                LOGGER.log(WARNING, null, x);
            }
        }
        return rcl;
    }
    /** JENKINS-6604 */
    static boolean TESTING;

    private Class<?> loadClassFile(String name, byte[] bytes) {
        // if someone else is forcing us to load a class by giving as bytecode,
        // discard our prefetched version since we'll never use them.
        prefetchedClasses.remove(name);

        // define package
        definePackage(name);

        try {
            return defineClass(name, bytes, 0, bytes.length);
        } catch (ClassFormatError e) {
            throw (ClassFormatError)new ClassFormatError("Failed to load "+name).initCause(e);
        }
    }

    /**
     * Defining a package is necessary to make {@link Class#getPackage()} work,
     * which is often used to retrieve package-level annotations.
     * (for example, JAXB RI and Hadoop use them.) 
     */
    private void definePackage(String name) {
        int idx = name.lastIndexOf('.');
        if (idx<0)  return; // not in a package
        
        String packageName = name.substring(0,idx);
        if (getPackage(packageName) != null)    // already defined
            return;

        definePackage(packageName, null, null, null, null, null, null, null);
    }

    public URL findResource(String name) {
        // first attempt to load from locally fetched jars
        URL url = super.findResource(name);
        if(url!=null || channel.isRestricted())   return url;

        try {
            if(resourceMap.containsKey(name)) {
                URLish f = resourceMap.get(name);
                if(f==null) return null;    // no such resource
                URL u = f.toURL();
                if (u!=null)    return u;
            }

            long startTime = System.nanoTime();

            ResourceFile r = proxy.getResource2(name);
            ResourceImageRef image=null;
            if (r!=null)    image=r.image;

            channel.resourceLoadingTime.addAndGet(System.nanoTime()-startTime);
            channel.resourceLoadingCount.incrementAndGet();
            if(image==null) {
                resourceMap.put(name,null);
                return null;
            }

            URLish res = image.resolveURL(channel, name);
            resourceMap.put(name,res);
            return res.toURL();
        } catch (IOException e) {
            throw new Error("Unable to load resource "+name,e);
        } catch (InterruptedException e) {
            throw new Error("Unable to load resource "+name,e);
        }
    }

    private static Vector<URL> toURLs(Vector<URLish> src) throws MalformedURLException {
        Vector<URL> r = new Vector<URL>(src.size());
        for (URLish s : src) {
            URL u = s.toURL();
            if (u==null)    return null;    // abort
            r.add(u);
        }
        return r;
    }

    public Enumeration<URL> findResources(String name) throws IOException {
        if(channel.isRestricted())
            return EMPTY_ENUMERATION;

        // TODO: use the locally fetched jars to speed up the look up
        // the challenge is how to combine the list from local jars
        // and the remote list

        Vector<URLish> v = resourcesMap.get(name);
        if(v!=null) {
            Vector<URL> urls = toURLs(v);
            if(urls!=null)
                return urls.elements();
        }

        long startTime = System.nanoTime();
        ResourceFile[] images = proxy.getResources2(name);
        channel.resourceLoadingTime.addAndGet(System.nanoTime()-startTime);
        channel.resourceLoadingCount.incrementAndGet();

        v = new Vector<URLish>();
        for( ResourceFile image: images )
            try {
                v.add(image.image.resolveURL(channel,name));
            } catch (InterruptedException e) {
                throw (Error)new Error("Failed to load resources "+name).initCause(e);
            }
        resourcesMap.put(name,v);

        return toURLs(v).elements();
    }

    /**
     * @deprecated Use {@link Util#deleteDirectoryOnExit(File)}
     */
    public static void deleteDirectoryOnExit(File dir) {
        Util.deleteDirectoryOnExit(dir);
    }


    /**
     * Prefetches the jar into this class loader.
     *
     * @param jar
     *      Jar to be prefetched. Note that this file is an file on the other end,
     *      and doesn't point to anything meaningful locally.
     * @return
     *      true if the prefetch happened. false if the jar is already prefetched.
     * @see Channel#preloadJar(Callable, Class[]) 
     */
    /*package*/ boolean prefetch(URL jar) throws IOException {
        synchronized (prefetchedJars) {
            if(prefetchedJars.contains(jar))
                return false;

            String p = jar.getPath().replace('\\','/');
            p = getBaseName(p);
            File localJar = makeResource(p,proxy.fetchJar(jar));
            addURL(localJar.toURI().toURL());
            prefetchedJars.add(jar);
            return true;
        }
    }

    // for local ref
    public static class ClassReference {
        final ClassLoader classLoader;
        final ResourceImageRef classImage;

        public ClassReference(Channel channel, ClassFile wireFormat) {
            this.classLoader = channel.importedClassLoaders.get(wireFormat.classLoader);
            this.classImage = new ResourceImageDirect(wireFormat.classImage);
        }

        public ClassReference(Channel channel, ClassFile2 wireFormat) {
            // TODO: importedClassLoaders.get looks awfully inefficient
            this.classLoader = channel.importedClassLoaders.get(wireFormat.classLoader);
            this.classImage = wireFormat.image;
        }
    }

    /**
     * Wire format that we used to use for transferring a class file.
     *
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

        public ClassFile2 upconvert(URL local) {
            return new ClassFile2(classLoader,new ResourceImageDirect(classImage),local);
        }
    }

    /**
     * Wire format that we use to transfer a resource file.
     *
     * {@link Capability#supportsPrefetch()} enables this feature
     */
    public static class ResourceFile implements Serializable {
        /**
         * Encapsulates how to retrieve the actual resource.
         */
        final ResourceImageRef image;

        /**
         * While this object is still on the sender side, an object that allows
         * this side to read its content.
         */
        transient final URL local;

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
     * A class file is a kind of a {@link ResourceFile}
     */
    public static class ClassFile2 extends ResourceFile {
        /**
         * oid of the classloader that should load this class.
         */
        final int classLoader;

        ClassFile2(int classLoader, URL local) throws IOException {
            this(classLoader,new ResourceImageDirect(local), local);
        }

        ClassFile2(int classLoader, ResourceImageRef image, URL local) {
            super(image,local);
            this.classLoader = classLoader;
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * Remoting interface.
     */
    public static interface IClassLoader {
        byte[] fetchJar(URL url) throws IOException;

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

        byte[] getResource(String name) throws IOException;
        byte[][] getResources(String name) throws IOException;

    // the rest is added as a part of Capability.supportsPrefetch()
        /**
         * {@link #fetch2(String)} plus pre-fetch.
         *
         * The callee may return additional {@link ClassFile}s that it expects
         * to get loaded in a near future. This avoids repeated invocations of {@link #fetch2(String)}
         * thereby reducing the # of roundtrips.
         *
         * @since 2.PREFETCH
         * @see Capability#supportsPrefetch()
         */
        Map<String,ClassFile2> fetch3(String className) throws ClassNotFoundException;

        /**
         * Remoting equivalent of {@link ClassLoader#getResource(String)}
         *
         * @return
         *      null if the resource is not found.
         */
        ResourceFile getResource2(String name) throws IOException;

        /**
         * Remoting equivalent of {@link ClassLoader#getResources(String)}
         *
         * @return
         *      never null
         */
        ResourceFile[] getResources2(String name) throws IOException;
    }

    public static IClassLoader export(ClassLoader cl, Channel local) {
        if (cl instanceof RemoteClassLoader) {
            // check if this is a remote classloader from the channel
            final RemoteClassLoader rcl = (RemoteClassLoader) cl;
            int oid = RemoteInvocationHandler.unwrap(rcl.proxy, local);
            if(oid!=-1) {
                return new RemoteIClassLoader(oid,rcl.proxy);
            }
        }
        return local.export(IClassLoader.class, new ClassLoaderProxy(cl,local), false);
    }

    public static void pin(ClassLoader cl, Channel local) {
        if (cl instanceof RemoteClassLoader) {
            // check if this is a remote classloader from the channel
            final RemoteClassLoader rcl = (RemoteClassLoader) cl;
            int oid = RemoteInvocationHandler.unwrap(rcl.proxy, local);
            if(oid!=-1) return;
        }
        local.pin(new ClassLoaderProxy(cl,local));
    }

    /**
     * Exports and just returns the object ID, instead of obtaining the proxy.
     */
    static int exportId(ClassLoader cl, Channel local) {
        return local.export(new ClassLoaderProxy(cl,local), false);
    }

    /*package*/ static final class ClassLoaderProxy implements IClassLoader {
        final ClassLoader cl;
        final Channel channel;
        /**
         * Class names that we've already sent to the other side as pre-fetch.
         */
        private final Set<String> prefetched = new HashSet<String>();

        public ClassLoaderProxy(ClassLoader cl, Channel channel) {
        	assert cl != null;

            this.cl = cl;
            this.channel = channel;
        }

        public byte[] fetchJar(URL url) throws IOException {
            return readFully(url.openStream());
        }

        public byte[] fetch(String className) throws ClassNotFoundException {
        	if (!USE_BOOTSTRAP_CLASSLOADER && cl==PSEUDO_BOOTSTRAP) {
        		throw new ClassNotFoundException("Classloading from bootstrap classloader disabled");
        	}
        	
            InputStream in = cl.getResourceAsStream(className.replace('.', '/') + ".class");
            if(in==null)
                throw new ClassNotFoundException(className);

            try {
                return readFully(in);
            } catch (IOException e) {
                throw new ClassNotFoundException();
            }
        }

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
                if(in==null)
                    throw new ClassNotFoundException(className);
                return new ClassFile(
                        exportId(ecl,channel),
                        readFully(in));
            } catch (IOException e) {
                throw new ClassNotFoundException();
            }
        }

        public ClassFile2 fetch4(String className) throws ClassNotFoundException {
            Class<?> c = cl.loadClass(className);
            ClassLoader ecl = c.getClassLoader();
            if (ecl == null) {
            	if (USE_BOOTSTRAP_CLASSLOADER) {
            		ecl = PSEUDO_BOOTSTRAP;
            	} else {
            		throw new ClassNotFoundException("Classloading from system classloader disabled");
            	}
            }

            try {
                final URL urlOfClassFile = Which.classFileUrl(c);

                try {
                    File jar = Which.jarFile(c);
                    if (jar.isFile()) {// for historical reasons the jarFile method can return a directory
                        Checksum sum = channel.jarLoader.calcChecksum(jar);
                        return new ClassFile2(exportId(ecl,channel),
                                new ResourceImageInJar(sum,null /* TODO: we need to check if the URL of c points to the expected location of the file */),urlOfClassFile);
                    }
                } catch (IllegalArgumentException e) {
                    // we determined that 'c' isn't in a jar file
                    LOGGER.log(FINE,c+" isn't in a jar file: "+urlOfClassFile,e);
                }
                return fetch2(className).upconvert(urlOfClassFile);
            } catch (IOException e) {
                throw new ClassNotFoundException();
            }
        }

        public Map<String,ClassFile2> fetch3(String className) throws ClassNotFoundException {
            ClassFile2 cf = fetch4(className);
            Map<String,ClassFile2> all = new HashMap<String,ClassFile2>();
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
                        // XXX could even traverse second-level dependencies, etc.
                        all.put(other, fetch4(other));
                    } catch (ClassNotFoundException x) {
                        // ignore: might not be real class name, etc.
                    }
                }
            } catch (IOException e) {
                LOGGER.log(WARNING, "Failed to analyze the class file: "+cf.local, e);
                // ignore
            }
            return all;
        }

        private URL getResourceURL(String name) throws IOException {
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

        public ResourceFile getResource2(String name) throws IOException {
            URL resource = getResourceURL(name);
            if (resource == null) return null;

            return makeResource(name, resource);
        }

        private ResourceFile makeResource(String name, URL resource) throws IOException {
            try {
                File jar = Which.jarFile(resource, name);
                if (jar.isFile()) {// for historical reasons the jarFile method can return a directory
                    Checksum sum = channel.jarLoader.calcChecksum(jar);
                    return new ResourceFile(new ResourceImageInJar(sum,null),resource);
                }
            } catch (IllegalArgumentException e) {
                LOGGER.log(FINE,name+" isn't in a jar file: "+resource,e);
            }
            return new ResourceFile(resource);
        }

        public byte[] getResource(String name) throws IOException {
        	URL resource = getResourceURL(name);
        	if (resource == null)   return null;
            return readFully(resource.openStream());
        }

        public List<URL> getResourcesURL(String name) throws IOException {
            List<URL> images = new ArrayList<URL>();

            Set<URL> systemResources = null;
            if (!USE_BOOTSTRAP_CLASSLOADER) {
            	systemResources = new HashSet<URL>();
            	Enumeration<URL> e = PSEUDO_BOOTSTRAP.getResources(name);
            	while (e.hasMoreElements()) {
            		systemResources.add(e.nextElement());
            	}
            }

            Enumeration<URL> e = cl.getResources(name);
            while(e.hasMoreElements()) {
            	URL url = e.nextElement();
            	if (systemResources == null || !systemResources.contains(url)) {
            		images.add(url);
            	}
            }

            return images;
        }

        public byte[][] getResources(String name) throws IOException {
            List<URL> x = getResourcesURL(name);
            byte[][] r = new byte[x.size()][];
            for (int i = 0; i < r.length; i++)
                r[i] = readFully(x.get(i).openStream());
            return r;
        }

        public ResourceFile[] getResources2(String name) throws IOException {
            List<URL> x = getResourcesURL(name);
            ResourceFile[] r = new ResourceFile[x.size()];
            for (int i = 0; i < r.length; i++) {
                r[i] = makeResource(name,x.get(i));
            }
            return r;
        }

        public boolean equals(Object that) {
            if (this == that) return true;
            if (that == null || getClass() != that.getClass()) return false;

            return cl.equals(((ClassLoaderProxy) that).cl);
        }

        public int hashCode() {
            return cl.hashCode();
        }

        @Override
        public String toString() {
            return super.toString()+'['+cl.toString()+']';
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
        private static final ClassLoader PSEUDO_BOOTSTRAP = new URLClassLoader(new URL[0],null) {
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
    private static class RemoteIClassLoader implements IClassLoader, Serializable {
        private transient final IClassLoader proxy;
        private final int oid;

        private RemoteIClassLoader(int oid, IClassLoader proxy) {
            this.proxy = proxy;
            this.oid = oid;
        }

        public byte[] fetchJar(URL url) throws IOException {
            return proxy.fetchJar(url);
        }

        public byte[] fetch(String className) throws ClassNotFoundException {
            return proxy.fetch(className);
        }

        public ClassFile fetch2(String className) throws ClassNotFoundException {
            return proxy.fetch2(className);
        }

        public Map<String,ClassFile2> fetch3(String className) throws ClassNotFoundException {
            return proxy.fetch3(className);
        }

        public byte[] getResource(String name) throws IOException {
            return proxy.getResource(name);
        }

        public byte[][] getResources(String name) throws IOException {
            return proxy.getResources(name);
        }

        public ResourceFile getResource2(String name) throws IOException {
            return proxy.getResource2(name);
        }

        public ResourceFile[] getResources2(String name) throws IOException {
            return proxy.getResources2(name);
        }

        private Object readResolve() {
            return Channel.current().getExportedObject(oid);
        }

        private static final long serialVersionUID = 1L;
    }

    private static Iterable<String> analyze(InputStream bytecode) {
        // Other options include ASM and org.apache.tools.ant.taskdefs.optional.depend.constantpool.ConstantPool.
        try {
            return ConstantPoolScanner.dependencies(bytecode);
        } catch (IOException x) {
            LOGGER.log(WARNING, "could not parse bytecode", x);
            return Collections.emptySet();
        }
    }
 
    /**
     * If set to true, classes loaded by the bootstrap classloader will be also remoted to the remote JVM.
     * By default, classes that belong to the bootstrap classloader will NOT be remoted, as each JVM gets its own JRE
     * and their versions can be potentially different.
     */
    public static boolean USE_BOOTSTRAP_CLASSLOADER = Boolean.getBoolean(RemoteClassLoader.class.getName() + ".useBootstrapClassLoader");

    private static final Enumeration<URL> EMPTY_ENUMERATION = new Vector<URL>().elements();
}
