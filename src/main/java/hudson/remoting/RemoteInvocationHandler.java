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

import org.jenkinsci.remoting.RoleChecker;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sits behind a proxy object and implements the proxy logic.
 *
 * @author Kohsuke Kawaguchi
 */
final class RemoteInvocationHandler implements InvocationHandler, Serializable {
    /**
     * Our logger.
     */
    private static final Logger logger = Logger.getLogger(RemoteInvocationHandler.class.getName());
    /**
     * The {@link Unexporter} to track {@link RemoteInvocationHandler} instances that should be unexported when
     * collected by the garbage collector.
     * @since FIXME after merge
     */
    private static final Unexporter UNEXPORTER = new Unexporter();
    /**
     * This proxy acts as a proxy to the object of
     * Object ID on the remote {@link Channel}.
     */
    private final int oid;

    /**
     * Represents the connection to the remote {@link Channel}.
     *
     * <p>
     * This field is null when a {@link RemoteInvocationHandler} is just
     * created and not working as a remote proxy. Once tranferred to the
     * remote system, this field is set to non-null. 
     * @since FIXME after merge
     */
    @CheckForNull
    private transient Channel.Ref channel;

    /**
     * True if we are proxying an user object.
     */
    private final boolean userProxy;

    /**
     * If true, this proxy is automatically unexported by the calling {@link Channel},
     * so this object won't release the object at {@link #finalize()}.
     * <p>
     * This ugly distinction enables us to keep the # of exported objects low for
     * the typical situation where the calls are synchronous (thus end of the call
     * signifies full unexport of all involved objects.)
     */
    private final boolean autoUnexportByCaller;

    /**
     * If true, indicates that this proxy object is being sent back
     * to where it came from. If false, indicates that this proxy
     * is being sent to the remote peer.
     *
     * Only used in the serialized form of this class.
     */
    private boolean goingHome;

    /**
     * Diagnostic information that remembers where the proxy was created.
     */
    private final Throwable origin;

    /**
     * Creates a proxy that wraps an existing OID on the remote.
     */
    RemoteInvocationHandler(Channel channel, int id, boolean userProxy, boolean autoUnexportByCaller, Class proxyType) {
        this.channel = channel == null ? null : channel.ref();
        this.oid = id;
        this.userProxy = userProxy;
        this.origin = new Exception("Proxy "+toString()+" was created for "+proxyType);
        this.autoUnexportByCaller = autoUnexportByCaller;
    }

    /**
     * Wraps an OID to the typed wrapper.
     */
    public static <T> T wrap(Channel channel, int id, Class<T> type, boolean userProxy, boolean autoUnexportByCaller) {
        ClassLoader cl = type.getClassLoader();
        // if the type is a JDK-defined type, classloader should be for IReadResolve
        if(cl==null || cl==ClassLoader.getSystemClassLoader())
            cl = IReadResolve.class.getClassLoader();
        RemoteInvocationHandler handler = new RemoteInvocationHandler(channel, id, userProxy, autoUnexportByCaller, type);
        if (channel != null) {
            if (!autoUnexportByCaller) {
                UNEXPORTER.watch(handler);
            }
        }
        return type.cast(Proxy.newProxyInstance(cl, new Class[]{type, IReadResolve.class}, handler));
    }

    /**
     * Called as soon as a channel is terminated, we cannot use {@link Channel.Listener} as that is called after
     * termination has completed and we need to clean up the {@link PhantomReferenceImpl} before they try to
     * clean themselves up by sending the {@link UnexportCommand} over the closing {@link Channel}.
     *
     * @param channel the {@link Channel} that is terminating/terminated.
     * @since FIXME after merge
     */
    /*package*/ static void notifyChannelTermination(Channel channel) {
        UNEXPORTER.onChannelTermination(channel);
    }
    
    /*package*/ static Class getProxyClass(Class type) {
        return Proxy.getProxyClass(type.getClassLoader(), new Class[]{type,IReadResolve.class});
    }

    /**
     * Returns the backing channel or {@code null} if the channel is disconnected or otherwise unavailable.
     *
     * @return the backing channel or {@code null}.
     * @since FIXME after merge
     */
    @CheckForNull
    private Channel channel() {
        return this.channel == null ? null : this.channel.channel();
    }
    
    /**
     * If the given object is a proxy to a remote object in the specified channel,
     * return its object ID. Otherwise return -1.
     * <p>
     * This method can be used to get back the original reference when
     * a proxy is sent back to the channel it came from. 
     */
    public static int unwrap(Object proxy, Channel src) {
        InvocationHandler h = Proxy.getInvocationHandler(proxy);
        if (h instanceof RemoteInvocationHandler) {
            RemoteInvocationHandler rih = (RemoteInvocationHandler) h;
            if(rih.channel()==src)
                return rih.oid;
        }
        return -1;
    }

    /**
     * If the given object is a proxy object, return the {@link Channel}
     * object that it's associated with. Otherwise null.
     */
    public static Channel unwrap(Object proxy) {
        InvocationHandler h = Proxy.getInvocationHandler(proxy);
        if (h instanceof RemoteInvocationHandler) {
            RemoteInvocationHandler rih = (RemoteInvocationHandler) h;
            return rih.channel();
        }
        return null;
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if(method.getDeclaringClass()==IReadResolve.class) {
            // readResolve on the proxy.
            // if we are going back to where we came from, replace the proxy by the real object
            if(goingHome)   return Channel.current().getExportedObject(oid);
            else            return proxy;
        }

        if(channel==null)
            throw new IllegalStateException("proxy is not connected to a channel");

        if(args==null)  args = EMPTY_ARRAY;

        Class<?> dc = method.getDeclaringClass();
        if(dc ==Object.class) {
            // handle equals and hashCode by ourselves
            try {
                return method.invoke(this,args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        // delegate the rest of the methods to the remote object

        boolean async = method.isAnnotationPresent(Asynchronous.class);
        RPCRequest req = new RPCRequest(oid, method, args, userProxy ? dc.getClassLoader() : null);
        try {
            if(userProxy) {
                if (async)  channel().callAsync(req);
                else        return channel().call(req);
            } else {
                if (async)  req.callAsync(channel());
                else        return req.call(channel());
            }
            return null;
        } catch (Throwable e) {
            for (Class exc : method.getExceptionTypes()) {
                if (exc.isInstance(e))
                    throw e;    // signature explicitly lists this exception
            }
            if (e instanceof RuntimeException || e instanceof Error)
                throw e;    // these can be thrown from any methods

            // if the thrown exception type isn't compatible with the method signature
            // wrap it to RuntimeException to avoid UndeclaredThrowableException
            throw new RemotingSystemException(e);
        }
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        if (goingHome) {
            // We came back to the side that exported the object.
            // Since this object represents a local object, it shouldn't have non-null channel.
            // (which would cause the finalize() method to try to unexport the object.)
            channel = null;
        } else {
            Channel channel = Channel.current();
            this.channel = channel == null ? null : channel.ref();
            if (channel != null) {
                if (!autoUnexportByCaller) {
                    UNEXPORTER.watch(this);
                }
            }
        }
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        goingHome = channel!=null;
        oos.defaultWriteObject();
    }

    /**
     * Two proxies are the same iff they represent the same remote object. 
     */
    public boolean equals(Object o) {
        if(o!=null && Proxy.isProxyClass(o.getClass()))
            o = Proxy.getInvocationHandler(o);

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RemoteInvocationHandler that = (RemoteInvocationHandler) o;

        return this.oid==that.oid && this.channel==that.channel;

    }

    public int hashCode() {
        return oid;
    }

    /**
     * Finalizers are run only under extreme GC pressure whereas {@link PhantomReference} are cleared out
     * more quickly, thus we use a {@link PhantomReference} in place of the override of {@link Object#finalize()}
     * that was previously used in order to unexport.
     * @since FIXME after merge
     */
    private static class PhantomReferenceImpl extends PhantomReference<RemoteInvocationHandler> {

        /**
         * The object id to unexport.
         */
        private final int oid;
        /**
         * The origin from where the object was created.
         */
        private Throwable origin;
        /**
         * The reference to the channel on which to unexport.
         */
        private Channel.Ref channel;

        /**
         * Construct our reference and bind to the {@link ReferenceQueue} after capturing the required state for
         * {@link #cleanup()}.
         *
         * @param referent       the {@link RemoteInvocationHandler} we will clean up.
         * @param referenceQueue the {@link ReferenceQueue}
         */
        private PhantomReferenceImpl(RemoteInvocationHandler referent,
                                    ReferenceQueue<? super RemoteInvocationHandler> referenceQueue) {
            super(referent, referenceQueue);
            this.oid = referent.oid;
            this.origin = referent.origin;
            this.channel = referent.channel;
        }

        /**
         * Sends the {@link UnexportCommand} for the specified {@link #oid} if the {@link Channel} is still open.
         * @throws IOException if the {@link UnexportCommand} could not be sent.
         */
        private void cleanup() throws IOException {
            if (this.channel == null) {
                return;
            }
            Channel channel = this.channel.channel();
            if (channel != null && !channel.isClosingOrClosed()) {
                try {
                    channel.send(new UnexportCommand(oid, origin));
                } finally {
                    // clear out references to simplify GC
                    this.origin = null;
                    this.channel = null;
                }
            }
        }
    }

    /**
     * Manages the cleanup of {@link RemoteInvocationHandler} instances that need to be auto unexported.
     * @since FIXME after merge
     */
    private static class Unexporter implements Runnable {

        /**
         * Our executor service, we use at most one thread for all {@link Channel} instances in the current classloader.
         */
        private final ExecutorService svc = new AtmostOneThreadExecutor(
                new NamingThreadFactory(new DaemonThreadFactory(), RemoteInvocationHandler.class.getSimpleName())
        );
        /**
         * Flag to track that {@link #UNEXPORTER} has been queued for execution.
         */
        private final AtomicBoolean inQueue = new AtomicBoolean(false);
        /**
         * Flag to track that {@link #UNEXPORTER} is running.
         */
        private final AtomicBoolean isAlive = new AtomicBoolean(false);
        /**
         * Our {@link ReferenceQueue} for picking up references that have been collected by the garbage collector
         * and need the corresponding {@link UnexportCommand} sent.
         */
        private final ReferenceQueue<? super RemoteInvocationHandler> queue = new ReferenceQueue<RemoteInvocationHandler>();
        /**
         * The "live" {@link PhantomReferenceImpl} instances for each active {@link Channel}.
         */
        private final ConcurrentMap<Channel.Ref,List<PhantomReferenceImpl>> referenceLists 
                = new ConcurrentHashMap<Channel.Ref, List<PhantomReferenceImpl>>();

        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            if (!isAlive.compareAndSet(false, true)) {
                inQueue.set(false); // we have started execution so clear the flag, must happen after check of running
                return;
            }
            inQueue.set(false); // we have started execution and running is true, so queued can be reset
            try {
                while (!referenceLists.isEmpty()) {
                    try {
                        Reference<?> ref = queue.remove(100);
                        if (ref instanceof PhantomReferenceImpl) {
                            PhantomReferenceImpl r = (PhantomReferenceImpl) ref;
                            final Channel.Ref channelRef = r.channel;
                            try {
                                r.cleanup();
                            } catch (IOException e) {
                                logger.log(Level.WARNING,
                                        String.format("Couldn't clean up oid=%d from %s", r.oid, r.origin), e);
                            } catch (Error e) {
                                logger.log(Level.SEVERE,
                                        String.format("Couldn't clean up oid=%d from %s", r.oid, r.origin), e);
                                throw e; // pass on as there is nothing we can do with an error
                            } catch (Throwable e) {
                                logger.log(Level.WARNING,
                                        String.format("Couldn't clean up oid=%d from %s", r.oid, r.origin), e);
                            } finally {
                                if (channelRef != null) {
                                    final List<PhantomReferenceImpl> referenceList = referenceLists.get(channelRef);
                                    if (referenceList != null) {
                                        referenceList.remove(r);
                                        if (channelRef.channel() == null) {
                                            cleanList(referenceList);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        logger.log(Level.FINE, "Interrupted", e);
                    }
                    // purge any dead channels, it does not matter if we spend a long time here as we are
                    // removing future potential work for us from ever entering the queue and freeing garbage
                    for (Iterator<Map.Entry<Channel.Ref, List<PhantomReferenceImpl>>>
                         iterator = referenceLists.entrySet().iterator(); 
                         iterator.hasNext(); ) {
                        final Map.Entry<Channel.Ref, List<PhantomReferenceImpl>> entry = iterator.next();
                        final Channel.Ref r = entry.getKey();
                        if (r == null || r.channel() == null) {
                            iterator.remove();
                            // take them out of the queue
                            cleanList(entry.getValue());
                        }
                    }
                }
            } finally {
                isAlive.set(false);
            }
        }

        /**
         * Cleans a {@link List} of {@link PhantomReferenceImpl} as the {@link Channel} has closed.
         *
         * @param referenceList the {@link List}.
         */
        private void cleanList(@CheckForNull List<PhantomReferenceImpl> referenceList) {
            if (referenceList == null) {
                return;
            }
            for (PhantomReferenceImpl phantom: referenceList) {
                phantom.clear();
            }
            // simplify life for the Garbage collector by reducing reference counts
            referenceList.clear();
        }

        /**
         * Watch the specified {@link RemoteInvocationHandler} for garbage collection so that it can be unexported
         * when collected.
         * 
         * @param handler the {@link RemoteInvocationHandler} instance to watch.
         */
        private void watch(RemoteInvocationHandler handler) {
            Channel.Ref ref = handler.channel;
            if (ref == null || ref.channel() == null) {
                // channel is dead anyway, so we could not send an UnexportCommand even if we wanted to
                return;
            }
            List<PhantomReferenceImpl> referenceList;
            while (null == (referenceList = referenceLists.get(ref))) {
                referenceLists.putIfAbsent(ref, Collections.synchronizedList(new ArrayList<PhantomReferenceImpl>()));
            }
            referenceList.add(new PhantomReferenceImpl(handler, queue));
            if (isAlive.get()) {
                // if already running we are all set and can return
                return;
            }
            // ok, we may need to schedule another execution
            if (inQueue.compareAndSet(false, true)) {
                // let's submit... if there are multiple instances in the queue, only one will run at a time
                // and when it finishes the others will either exit due to and empty referencesList or else
                // we wanted them running anyway
                try {
                    svc.submit(UNEXPORTER);
                } catch (RejectedExecutionException e) {
                    // we must be in the process of being shut down
                }
            }
        }

        /**
         * Stop watching {@link RemoteInvocationHandler} instances for the specified {@link Channel} as it is
         * terminating/ed.
         * @param channel the {@link Channel}.
         */
        private void onChannelTermination(Channel channel) {
            cleanList(referenceLists.remove(channel.ref()));
        }
    }
    
    private static final long serialVersionUID = 1L;

    /**
     * Executes the method call remotely.
     *
     * If used as {@link Request}, this can be used to provide a lower-layer
     * for the use inside remoting, to implement the classloader delegation, and etc.
     * The downside of this is that the classes used as a parameter/return value
     * must be available to both JVMs.
     *
     * If used as {@link Callable} in conjunction with {@link UserRequest},
     * this can be used to send a method call to user-level objects, and
     * classes for the parameters and the return value are sent remotely if needed.
     */
    static final class RPCRequest extends Request<Serializable,Throwable> implements DelegatingCallable<Serializable,Throwable> {
        /**
         * Target object id to invoke.
         */
        private final int oid;

        private final String methodName;
        /**
         * Type name of the arguments to invoke. They are names because
         * neither {@link Method} nor {@link Class} is serializable.
         */
        private final String[] types;
        /**
         * Arguments to invoke the method with.
         */
        private final Object[] arguments;

        /**
         * If this is used as {@link Callable}, we need to remember what classloader
         * to be used to serialize the request and the response.
         */
        private transient ClassLoader classLoader;

        public RPCRequest(int oid, Method m, Object[] arguments) {
            this(oid,m,arguments,null);
        }

        public RPCRequest(int oid, Method m, Object[] arguments, ClassLoader cl) {
            this.oid = oid;
            this.arguments = arguments;
            this.methodName = m.getName();
            this.classLoader = cl;

            this.types = new String[arguments.length];
            Class<?>[] params = m.getParameterTypes();
            for( int i=0; i<arguments.length; i++ )
                types[i] = params[i].getName();
            assert types.length == arguments.length;
        }

        public Serializable call() throws Throwable {
            return perform(Channel.current());
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
            // this callable only executes public methods exported by this side, so these methods are assumed to be safe
        }

        public ClassLoader getClassLoader() {
            if(classLoader!=null)
                return classLoader;
            else
                return getClass().getClassLoader();
        }

        protected Serializable perform(Channel channel) throws Throwable {
            Object o = channel.getExportedObject(oid);
            Class[] clazz = channel.getExportedTypes(oid);
            try {
                Method m = choose(clazz);
                if(m==null)
                    throw new IllegalStateException("Unable to call " + methodName + ". No matching method found in " + Arrays.toString(clazz) + " for " + o);
                m.setAccessible(true);  // in case the class is not public
                Object r;
                try {
                    r = m.invoke(o, arguments);
                } catch (IllegalArgumentException x) {
                    throw new RemotingSystemException("failed to invoke " + m + " on " + o + Arrays.toString(arguments), x);
                }
                if (r==null || r instanceof Serializable)
                    return (Serializable) r;
                else
                    throw new RemotingSystemException(new ClassCastException(r.getClass()+" is returned from "+m+" on "+o.getClass()+" but it's not serializable"));
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        /**
         * Chooses the method to invoke.
         */
        private Method choose(Class[] interfaces) {
            for(Class clazz: interfaces) {
                OUTER:
                for (Method m : clazz.getMethods()) {
                    if (!m.getName().equals(methodName))
                        continue;
                    Class<?>[] paramTypes = m.getParameterTypes();
                    if (paramTypes.length != arguments.length)
                        continue;
                    for (int i = 0; i < types.length; i++) {
                        if (!types[i].equals(paramTypes[i].getName()))
                            continue OUTER;
                    }
                    return m;
                }
            }
            return null;
        }

        Object[] getArguments() { // for debugging
            return arguments;
        }

        public String toString() {
            return "RPCRequest("+oid+","+methodName+")";
        }

        private static final long serialVersionUID = 1L; 
    }

    private static final Object[] EMPTY_ARRAY = new Object[0];
}
