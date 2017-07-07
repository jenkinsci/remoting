/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
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

import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import hudson.remoting.CommandTransport.CommandReceiver;
import hudson.remoting.PipeWindow.Key;
import hudson.remoting.PipeWindow.Real;
import hudson.remoting.forward.ForwarderFactory;
import hudson.remoting.forward.ListeningPort;
import hudson.remoting.forward.PortForwarder;
import org.jenkinsci.remoting.CallableDecorator;
import org.jenkinsci.remoting.RoleChecker;
import org.jenkinsci.remoting.nio.NioChannelHub;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Collections;
import java.util.Date;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Represents a communication channel to the remote peer.
 *
 * <p>
 * A {@link Channel} is a mechanism for two JVMs to communicate over
 * bi-directional {@link InputStream}/{@link OutputStream} pair.
 * {@link Channel} represents an endpoint of the stream, and thus
 * two {@link Channel}s are always used in a pair.
 *
 * <p>
 * Communication is established as soon as two {@link Channel} instances
 * are created at the end fo the stream pair
 * until the stream is terminated via {@link #close()}.
 *
 * <p>
 * The basic unit of remoting is an executable {@link Callable} object.
 * An application can create a {@link Callable} object, and execute it remotely
 * by using the {@link #call(Callable)} method or {@link #callAsync(Callable)} method.
 *
 * <p>
 * In this sense, {@link Channel} is a mechanism to delegate/offload computation
 * to other JVMs and somewhat like an agent system. This is bit different from
 * remoting technologies like CORBA or web services, where the server exposes a
 * certain functionality that clients invoke. 
 *
 * <p>
 * {@link Callable} object, as well as the return value / exceptions,
 * are transported by using Java serialization. All the necessary class files
 * are also shipped over {@link Channel} on-demand, so there's no need to
 * pre-deploy such classes on both JVMs. 
 *
 *
 * <h2>Implementor's Note</h2>
 * <p>
 * {@link Channel} builds its features in a layered model. Its higher-layer
 * features are built on top of its lower-layer features, and they
 * are called layer-0, layer-1, etc.
 *
 * <ul>
 * <li>
 *  <b>Layer 0</b>:
 *  See {@link Command} for more details. This is for higher-level features,
 *  and not likely useful for applications directly.
 * <li>
 *  <b>Layer 1</b>:
 *  See {@link Request} for more details. This is for higher-level features,
 *  and not likely useful for applications directly.
 * </ul>
 *
 * @author Kohsuke Kawaguchi
 */
public class Channel implements VirtualChannel, IChannel, Closeable {
    private final CommandTransport transport;

    /**
     * {@link OutputStream} that's given to the constructor. This is the hand-off with the lower layer.
     *
     * @deprecated
     *      See {@link #getUnderlyingOutput()}.
     */
    @Deprecated
    private final OutputStream underlyingOutput;

    /**
     * Human readable description of where this channel is connected to. Used during diagnostic output
     * and error reports.
     */
    private final String name;
    private volatile boolean remoteClassLoadingAllowed, arbitraryCallableAllowed;
    /*package*/ final CallableDecoratorList decorators = new CallableDecoratorList();
    /*package*/ final ExecutorService executor;

    /**
     * If non-null, the incoming link is already shut down,
     * and reader is already terminated. The {@link Throwable} object indicates why the outgoing channel
     * was closed.
     */
    private volatile Throwable inClosed = null;
    /**
     * If non-null, the outgoing link is already shut down,
     * and no command can be sent. The {@link Throwable} object indicates why the outgoing channel
     * was closed.
     */
    private volatile Throwable outClosed = null;

    /**
     * Requests that are sent to the remote side for execution, yet we are waiting locally until
     * we hear back their responses.
     */
    /*package*/ final Map<Integer,Request<?,?>> pendingCalls = new Hashtable<Integer,Request<?,?>>();

    /**
     * Remembers last I/O ID issued from locally to the other side, per thread.
     * int[1] is used as a holder of int.
     */
    private final ThreadLocal<int[]> lastIoId = new ThreadLastIoId();

    /**
     * Records the {@link Request}s being executed on this channel, sent by the remote peer.
     */
    /*package*/ final Map<Integer,Request<?,?>> executingCalls =
        Collections.synchronizedMap(new Hashtable<Integer,Request<?,?>>());

    /**
     * {@link ClassLoader}s that are proxies of the remote classloaders.
     */
    /*package*/ final ImportedClassLoaderTable importedClassLoaders;

    /**
     * Objects exported via {@link #export(Class, Object)}.
     */
    /*package (for test)*/ final ExportTable exportedObjects = new ExportTable();

    /**
     * {@link PipeWindow}s keyed by their OIDs (of the OutputStream exported by the other side.)
     *
     * <p>
     * To make the GC of {@link PipeWindow} automatic, the use of weak references here are tricky.
     * A strong reference to {@link PipeWindow} is kept from {@link ProxyOutputStream}, and
     * this is the only strong reference. Thus while {@link ProxyOutputStream} is alive,
     * it keeps {@link PipeWindow} referenced, which in turn keeps its {@link PipeWindow.Real#key}
     * referenced, hence this map can be looked up by the OID. When the {@link ProxyOutputStream}
     * will be gone, the key is no longer strongly referenced, so it'll get cleaned up.
     *
     * <p>
     * In some race condition situation, it might be possible for us to lose the tracking of the collect
     * window size. But as long as we can be sure that there's only one {@link PipeWindow} instance
     * per OID, it will only result in a temporary spike in the effective window size,
     * and therefore should be OK.
     */
    private final WeakHashMap<PipeWindow.Key, WeakReference<PipeWindow>> pipeWindows = new WeakHashMap<PipeWindow.Key, WeakReference<PipeWindow>>();
    /**
     * There are cases where complex object cycles can cause a closed channel to fail to be garbage collected,
     * these typically arrise when an {@link #export(Class, Object)} is {@link #setProperty(Object, Object)}
     * (a supported and intended use case), the {@link Ref} allows us to break the object cycle on channel
     * termination and simplify the circles into chains which can then be collected easily by the garbage collector.
     * @since FIXME after merge
     */
    private final Ref reference;

    /**
     * Registered listeners. 
     */
    private final Vector<Listener> listeners = new Vector<Listener>();
    private int gcCounter;

    /**
     * Number of {@link Command} objects sent to the other side.
     */
    private volatile long commandsSent;

    /**
     * Number of {@link Command} objects received from the other side.
     *
     * When a transport is functioning correctly, {@link #commandsSent} of one side
     * and {@link #commandsReceived} of the other side should closely match.
     */
    private volatile long commandsReceived;

    /**
     * Timestamp of the last {@link Command} object sent/received, in
     * {@link System#currentTimeMillis()} format.
     * This can be used as a basis for detecting dead connections.
     *
     * <p>
     * Note that {@link #lastCommandSentAt} doesn't mean
     * anything in terms of whether the underlying network was able to send
     * the data (for example, if the other end of a socket connection goes down
     * without telling us anything, the {@link SocketOutputStream#write(int)} will
     * return right away, and the socket only really times out after 10s of minutes.
     */
    private volatile long lastCommandSentAt, lastCommandReceivedAt;

    /**
     * Timestamp of when this channel was connected/created, in
     * {@link System#currentTimeMillis()} format.
     */
    private final long createdAt = System.currentTimeMillis();

    /**
     * Total number of nanoseconds spent for remote class loading.
     * <p>
     * Remote code execution often results in classloading activity
     * (more precisely, when the remote peer requests some computation
     * on this channel, this channel often has to load necessary
     * classes from the remote peer.)
     * <p>
     * This counter represents the total amount of time this channel
     * had to spend loading classes from the remote peer. The time
     * measurement doesn't include the time locally spent to actually
     * define the class (as the local classloading would have incurred
     * the same cost.)
     */
    public final AtomicLong classLoadingTime = new AtomicLong();

    /**
     * Total counts of remote classloading activities. Used in a pair
     * with {@link #classLoadingTime}.
     */
    public final AtomicInteger classLoadingCount = new AtomicInteger();

    /**
     * Prefetch cache hits.
     *
     * Out of all the counts in {@link #classLoadingCount}, how many times
     * were we able to resolve them by ourselves, saving a remote roundtrip call?
     * @since 2.24
     */
    public final AtomicInteger classLoadingPrefetchCacheCount = new AtomicInteger();

    /**
     * Total number of nanoseconds spent for remote resource loading.
     * @see #classLoadingTime
     */
    public final AtomicLong resourceLoadingTime = new AtomicLong();

    /**
     * Total count of remote resource loading.
     * @see #classLoadingCount
     */
    public final AtomicInteger resourceLoadingCount = new AtomicInteger();

    private final AtomicInteger ioId = new AtomicInteger();

    /**
     * Property bag that contains application-specific stuff.
     */
    private final Hashtable<Object,Object> properties = new Hashtable<Object,Object>();

    /**
     * Proxy to the remote {@link Channel} object.
     */
    private final IChannel remoteChannel;

    /**
     * Capability of the remote {@link Channel}.
     */
    public final Capability remoteCapability;

    /**
     * Single-thread executor for running pipe I/O operations.
     *
     * It is executed in a separate thread to avoid blocking the channel reader thread
     * in case read/write blocks. It is single thread to ensure FIFO; I/O needs to execute
     * in the same order the remote peer told us to execute them.
     */
    /*package*/ final PipeWriter pipeWriter;

    /**
     * ClassLaoder that remote classloaders should use as the basis.
     */
    /*package*/ final ClassLoader baseClassLoader;

    @Nonnull
    private JarCache jarCache;

    /*package*/ final JarLoaderImpl jarLoader;

    short maximumBytecodeLevel = Short.MAX_VALUE;

    /*package*/ @Nonnull final ClassFilter classFilter;

    /**
     * Communication mode used in conjunction with {@link ClassicCommandTransport}.
     * 
     * @since 1.161
     */
    public enum Mode {
        /**
         * Send binary data over the stream. Most efficient.
         */
        BINARY(new byte[]{0,0,0,0}),
        /**
         * Send ASCII over the stream. Uses base64, so the efficiency goes down by 33%,
         * but this is useful where stream is binary-unsafe, such as telnet.
         */
        TEXT("<===[HUDSON TRANSMISSION BEGINS]===>") {
            @Override protected OutputStream wrap(OutputStream os) {
                return BinarySafeStream.wrap(os);
            }
            @Override protected InputStream wrap(InputStream is) {
                return BinarySafeStream.wrap(is);
            }
        },
        /**
         * Let the remote peer decide the transmission mode and follow that.
         * Note that if both ends use NEGOTIATE, it will dead lock.
         */
        NEGOTIATE(new byte[0]);

        /**
         * Preamble used to indicate the tranmission mode.
         * Because of the algorithm we use to detect the preamble,
         * the string cannot be any random string. For example,
         * if the preamble is "AAB", we'll fail to find a preamble
         * in "AAAB".
         */
        /*package*/ final byte[] preamble;

        Mode(String preamble) {
            try {
                this.preamble = preamble.getBytes("US-ASCII");
            } catch (UnsupportedEncodingException e) {
                throw new Error(e);
            }
        }

        Mode(byte[] preamble) {
            this.preamble = preamble;
        }

        protected OutputStream wrap(OutputStream os) { return os; }
        protected InputStream wrap(InputStream is) { return is; }
    }

    /**
     * @deprecated as of 2.24
     *      Use {@link ChannelBuilder}
     */
    @Deprecated
    public Channel(String name, ExecutorService exec, InputStream is, OutputStream os) throws IOException {
        this(name,exec,Mode.BINARY,is,os,null);
    }

    /**
     * @deprecated as of 2.24
     *      Use {@link ChannelBuilder}
     */
    @Deprecated
    public Channel(String name, ExecutorService exec, Mode mode, InputStream is, OutputStream os) throws IOException {
        this(name,exec,mode,is,os,null);
    }

    /**
     * @deprecated as of 2.24
     *      Use {@link ChannelBuilder}
     */
    @Deprecated
    public Channel(String name, ExecutorService exec, InputStream is, OutputStream os, OutputStream header) throws IOException {
        this(name,exec,Mode.BINARY,is,os,header);
    }

    /**
     * @deprecated as of 2.24
     *      Use {@link ChannelBuilder}
     */
    @Deprecated
    public Channel(String name, ExecutorService exec, Mode mode, InputStream is, OutputStream os, OutputStream header) throws IOException {
        this(name,exec,mode,is,os,header,false);
    }

    /**
     * @deprecated as of 2.24
     *      Use {@link ChannelBuilder}
     */
    @Deprecated
    public Channel(String name, ExecutorService exec, Mode mode, InputStream is, OutputStream os, OutputStream header, boolean restricted) throws IOException {
        this(name,exec,mode,is,os,header,restricted,null);
    }

    /**
     * Creates a new channel.
     *
     * @param restricted
     * @deprecated as of 2.24
     *      Use {@link ChannelBuilder}
     */
    @Deprecated
    public Channel(String name, ExecutorService exec, Mode mode, InputStream is, OutputStream os, OutputStream header, boolean restricted, ClassLoader base) throws IOException {
        this(name,exec,mode,is,os,header,restricted,base,new Capability());
    }

    /*package*/ Channel(String name, ExecutorService exec, Mode mode, InputStream is, OutputStream os, OutputStream header, boolean restricted, ClassLoader base, Capability capability) throws IOException {
        this(new ChannelBuilder(name,exec)
                .withMode(mode)
                .withBaseLoader(base)
                .withCapability(capability)
                .withHeaderStream(header)
                .withRestricted(restricted), is, os);
    }

    /**
     * @deprecated as of 2.24
     *      Use {@link ChannelBuilder}
     * @since 2.13
     */
    @Deprecated
    public Channel(String name, ExecutorService exec, CommandTransport transport, boolean restricted, ClassLoader base) throws IOException {
        this(new ChannelBuilder(name,exec)
                .withBaseLoader(base)
                .withRestricted(restricted), transport);

    }

    /*package*/ Channel(ChannelBuilder settings, InputStream is, OutputStream os) throws IOException {
        this(settings, settings.negotiate(is,os));
    }

    /**
     * Creates a new channel.
     *
     * @param name
     *      See {@link #Channel(String, ExecutorService, Mode, InputStream, OutputStream, OutputStream, boolean, ClassLoader)}
     * @param exec
     *      See {@link #Channel(String, ExecutorService, Mode, InputStream, OutputStream, OutputStream, boolean, ClassLoader)}
     * @param transport
     *      The transport that we run {@link Channel} on top of.
     * @param base
     *      See {@link #Channel(String, ExecutorService, Mode, InputStream, OutputStream, OutputStream, boolean, ClassLoader)}
     * @param restricted
     *      See {@link #Channel(String, ExecutorService, Mode, InputStream, OutputStream, OutputStream, boolean, ClassLoader)}
     * @param jarCache
     *
     * @since 2.24
     * @deprecated as of 2.38
     */
    @Deprecated
    public Channel(String name, ExecutorService exec, CommandTransport transport, boolean restricted, ClassLoader base, JarCache jarCache) throws IOException {
        this(new ChannelBuilder(name,exec)
            .withBaseLoader(base)
            .withRestricted(restricted)
            .withJarCache(jarCache), transport);
    }

    /**
     * @since TODO
     */
    protected Channel(@Nonnull ChannelBuilder settings, @Nonnull CommandTransport transport) throws IOException {
        this.name = settings.getName();
        this.reference = new Ref(this);
        this.executor = new InterceptingExecutorService(settings.getExecutors(),decorators);
        this.arbitraryCallableAllowed = settings.isArbitraryCallableAllowed();
        this.remoteClassLoadingAllowed = settings.isRemoteClassLoadingAllowed();
        this.underlyingOutput = transport.getUnderlyingStream();
        this.importedClassLoaders = new ImportedClassLoaderTable(this, settings.isCacheFilesInJarURLConnections());
        
        // JAR Cache resolution
        JarCache effectiveJarCache = settings.getJarCache();
        if (effectiveJarCache == null) {
            effectiveJarCache = JarCache.getDefault();
            logger.log(Level.CONFIG, "Using the default JAR Cache: {0}", effectiveJarCache);
        }
        this.jarCache = effectiveJarCache;
                
        this.baseClassLoader = settings.getBaseLoader();
        this.classFilter = settings.getClassFilter();

        if(internalExport(IChannel.class, this, false)!=1)
            throw new AssertionError(); // export number 1 is reserved for the channel itself
        remoteChannel = RemoteInvocationHandler.wrap(this,1,IChannel.class,true,false);

        this.remoteCapability = transport.getRemoteCapability();
        this.pipeWriter = new PipeWriter(createPipeWriterExecutor());

        this.transport = transport;

        this.jarLoader = new JarLoaderImpl();
        setProperty(JarLoader.OURS, jarLoader);

        this.decorators.addAll(settings.getDecorators());
        this.properties.putAll(settings.getProperties());

        transport.setup(this, new CommandReceiver() {
            public void handle(Command cmd) {
                commandsReceived++;
                lastCommandReceivedAt = System.currentTimeMillis();
                if (logger.isLoggable(Level.FINE))
                    logger.fine("Received " + cmd);
                try {
                    cmd.execute(Channel.this);
                } catch (Throwable t) {
                    logger.log(Level.SEVERE, "Failed to execute command " + cmd + " (channel " + Channel.this.name + ")", t);
                    logger.log(Level.SEVERE, "This command is created here", cmd.createdAt);
                }
            }

            public void terminate(IOException e) {
                Channel.this.terminate(e);
            }
        });
        ACTIVE_CHANNELS.put(this,ref());
    }

    /**
     * Gets the {@link Ref} for this {@link Channel}. The {@link Ref} will be {@linkplain Ref#clear(Exception)}ed when
     * the channel is terminated in order to break any complex object cycles.
     * @return the {@link Ref} for this {@link Channel}
     * @since FIXME after merge
     */
    @Nonnull
    /*package*/ Ref ref() {
        return reference;
    }

    /**
     * Callback "interface" for changes in the state of {@link Channel}.
     */
    public static abstract class Listener {
        /**
         * When the channel was closed normally or abnormally due to an error.
         *
         * @param cause
         *      if the channel is closed abnormally, this parameter
         *      represents an exception that has triggered it.
         *      Otherwise null.
         */
        public void onClosed(Channel channel, IOException cause) {}
    }

    /**
     * Is the sender side of the transport already closed?
     */
    public boolean isOutClosed() {
        return outClosed!=null;
    }
    
    /**
     * Returns {@code true} if the channel is either in the process of closing down or has closed down.
     * @since 2.33
     */
    public boolean isClosingOrClosed() {
        return inClosed != null || outClosed != null;
    }

    /**
     * Creates the {@link ExecutorService} for writing to pipes.
     *
     * <p>
     * If the throttling is supported, use a separate thread to free up the main channel
     * reader thread (thus prevent blockage.) Otherwise let the channel reader thread do it,
     * which is the historical behaviour.
     */
    private ExecutorService createPipeWriterExecutor() {
        if (remoteCapability.supportsPipeThrottling())
            return new SingleLaneExecutorService(executor);
        return new SynchronousExecutorService();
    }

    /**
     * Sends a command to the remote end and executes it there.
     *
     * <p>
     * This is the lowest layer of abstraction in {@link Channel}.
     * {@link Command}s are executed on a remote system in the order they are sent.
     */
    /*package*/ synchronized void send(Command cmd) throws IOException {
        if(outClosed!=null)
            throw new ChannelClosedException(outClosed);
        if(logger.isLoggable(Level.FINE))
            logger.fine("Send "+cmd);

        transport.write(cmd, cmd instanceof CloseCommand);
        commandsSent++;
        lastCommandSentAt = System.currentTimeMillis();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T export(Class<T> type, T instance) {
        return export(type, instance, true);
    }

    /**
     * Exports the class instance to the remote side.
     * @param userProxy
     *      If true, the returned proxy will be capable to handle classes
     *      defined in the user classloader as parameters and return values.
     *      Such proxy relies on {@link RemoteClassLoader} and related mechanism,
     *      so it's not usable for implementing lower-layer services that are
     *      used by {@link RemoteClassLoader}.
     *
     *      To create proxies for objects inside remoting, pass in false. 
     * @return Reference to the exported instance wrapped by {@link RemoteInvocationHandler}. 
     *      {@code null} if the input instance is {@code null}.     
     */
    @Nullable
    /*package*/ <T> T export(Class<T> type, @CheckForNull T instance, boolean userProxy) {
        if(instance==null) {
            return null;
        }

        // every so often perform GC on the remote system so that
        // unused RemoteInvocationHandler get released, which triggers
        // unexport operation.
        if((++gcCounter)%10000==0)
            try {
                send(new GCCommand());
            } catch (IOException e) {
                // for compatibility reason we can't change the export method signature
                logger.log(Level.WARNING, "Unable to send GC command",e);
            }

        // either local side will auto-unexport, or the remote side will unexport when it's GC-ed
        boolean autoUnexportByCaller = exportedObjects.isRecording();
        final int id = internalExport(type, instance, autoUnexportByCaller);
        return RemoteInvocationHandler.wrap(null, id, type, userProxy, autoUnexportByCaller);
    }

    /*package*/ <T> int internalExport(Class<T> clazz, T instance) {
        return exportedObjects.export(clazz, instance);
    }

    /*package*/ <T> int internalExport(Class<T> clazz, T instance, boolean automaticUnexport) {
        return exportedObjects.export(clazz, instance, automaticUnexport);
    }

    /*package*/ @Nonnull Object getExportedObject(int oid) throws ExecutionException {
        return exportedObjects.get(oid);
    }
    
    @CheckForNull
    /*package*/ Object getExportedObjectOrNull(int oid)  {
        return exportedObjects.getOrNull(oid);
    }

    /*package*/ @Nonnull Class[] getExportedTypes(int oid) throws ExecutionException {
        return exportedObjects.type(oid);
    }

    /*package*/ void unexport(int id, Throwable cause) {
        unexport(id, cause, true);
    }
    
    /**
     * Unexports object.
     * @param id Object ID
     * @param cause Stacktrace pf the object creation call
     * @param severeErrorIfMissing Consider missing object as {@link #SEVERE} error. {@link #FINE} otherwise
     * @since TODO
     */
    /*package*/ void unexport(int id, Throwable cause, boolean severeErrorIfMissing) {
        exportedObjects.unexportByOid(id, cause, severeErrorIfMissing);
    }

    /**
     * Increase reference count so much to effectively prevent de-allocation.
     * @param instance Instance to be pinned
     */
    public void pin(@Nonnull Object instance) {
        exportedObjects.pin(instance);
    }

    /**
     * {@linkplain #pin(Object) Pin down} the exported classloader.
     */
    public void pinClassLoader(ClassLoader cl) {
        RemoteClassLoader.pin(cl,this);
    }

    /**
     * Preloads jar files on the remote side.
     *
     * <p>
     * This is a performance improvement method that can be safely
     * ignored if your goal is just to make things working.
     *
     * <p>
     * Normally, classes are transferred over the network one at a time,
     * on-demand. This design is mainly driven by how Java classloading works
     * &mdash; we can't predict what classes will be necessarily upfront very easily.
     *
     * <p>
     * Classes are loaded only once, so for long-running {@link Channel},
     * this is normally an acceptable overhead. But sometimes, for example
     * when a channel is short-lived, or when you know that you'll need
     * a majority of classes in certain jar files, then it is more efficient
     * to send a whole jar file over the network upfront and thereby
     * avoiding individual class transfer over the network.
     *
     * <p>
     * That is what this method does. It ensures that a series of jar files
     * are copied to the remote side (AKA "preloading.")
     * Classloading will consult the preloaded jars before performing
     * network transfer of class files.
     *
     * <p><strong>Beware</strong> that this method is not useful in all configurations.
     * If a {@link RemoteClassLoader} has another {@link RemoteClassLoader} as a
     * {@linkplain ClassLoader#getParent parent}, which would be typical, then preloading
     * a JAR in it will not reduce network round-trips: each class load still has to call
     * {@link ClassLoader#loadClass(String, boolean) loadClass} on the parent, which will
     * wind up checking the remote side just to get a negative answer.
     *
     * @param classLoaderRef
     *      This parameter is used to identify the remote classloader
     *      that will prefetch the specified jar files. That is, prefetching
     *      will ensure that prefetched jars will kick in
     *      when this {@link Callable} object is actually executed remote side.
     *
     *      <p>
     *      {@link RemoteClassLoader}s are created wisely, one per local {@link ClassLoader},
     *      so this parameter doesn't have to be exactly the same {@link Callable}
     *      to be executed later &mdash; it just has to be of the same class.
     * @param classesInJar
     *      {@link Class} objects that identify jar files to be preloaded.
     *      Jar files that contain the specified classes will be preloaded into the remote peer.
     *      You just need to specify one class per one jar.
     * @return
     *      true if the preloading actually happened. false if all the jars
     *      are already preloaded. This method is implemented in such a way that
     *      unnecessary jar file transfer will be avoided, and the return value
     *      will tell you if this optimization kicked in. Under normal circumstances
     *      your program shouldn't depend on this return value. It's just a hint.
     * @throws IOException
     *      if the preloading fails.
     */
    public boolean preloadJar(Callable<?,?> classLoaderRef, Class... classesInJar) throws IOException, InterruptedException {
        return preloadJar(UserRequest.getClassLoader(classLoaderRef), classesInJar);
    }

    public boolean preloadJar(ClassLoader local, Class... classesInJar) throws IOException, InterruptedException {
        URL[] jars = new URL[classesInJar.length];
        for (int i = 0; i < classesInJar.length; i++)
            jars[i] = Which.jarFile(classesInJar[i]).toURI().toURL();
        return call(new PreloadJarTask(jars, local));
    }

    public boolean preloadJar(ClassLoader local, URL... jars) throws IOException, InterruptedException {
        return call(new PreloadJarTask(jars,local));
    }

    /**
     * If this channel is built with jar file caching, return the object that manages this cache.
     * @since 2.24
     */
    @Nonnull
    public JarCache getJarCache() {
        return jarCache;
    }

    /**
     * You can change the {@link JarCache} while the channel is in operation,
     * but doing so doesn't impact {@link RemoteClassLoader}s that are already created.
     *
     * So to best avoid performance loss due to race condition, please set a JarCache in the constructor,
     * unless your call sequence guarantees that you call this method before remote classes are loaded.
     * @since 2.24
     */
    public void setJarCache(@Nonnull JarCache jarCache) {
        this.jarCache = jarCache;
    }

    /*package*/ PipeWindow getPipeWindow(int oid) {
        synchronized (pipeWindows) {
            Key k = new Key(oid);
            WeakReference<PipeWindow> v = pipeWindows.get(k);
            if (v!=null) {
                PipeWindow w = v.get();
                if (w!=null)
                    return w;
            }

            PipeWindow w;
            if (remoteCapability.supportsPipeThrottling())
                w = new Real(k, PIPE_WINDOW_SIZE);
            else
                w = new PipeWindow.Fake();
            pipeWindows.put(k,new WeakReference<PipeWindow>(w));
            return w;
        }
    }


    /**
     * {@inheritDoc}
     */
    public <V,T extends Throwable>
    V call(Callable<V,T> callable) throws IOException, T, InterruptedException {
        UserRequest<V,T> request=null;
        try {
            request = new UserRequest<V, T>(this, callable);
            UserResponse<V,T> r = request.call(this);
            return r.retrieve(this, UserRequest.getClassLoader(callable));

        // re-wrap the exception so that we can capture the stack trace of the caller.
        } catch (ClassNotFoundException e) {
            IOException x = new IOException("Remote call on "+name+" failed");
            x.initCause(e);
            throw x;
        } catch (Error e) {
            IOException x = new IOException("Remote call on "+name+" failed");
            x.initCause(e);
            throw x;
        } finally {
            // since this is synchronous operation, when the round trip is over
            // we assume all the exported objects are out of scope.
            // (that is, the operation shouldn't spawn a new thread or altter
            // global state in the remote system.
            if(request!=null)
                request.releaseExports();
        }
    }

    /**
     * {@inheritDoc}
     */
    public <V,T extends Throwable>
    Future<V> callAsync(final Callable<V,T> callable) throws IOException {
        final Future<UserResponse<V,T>> f = new UserRequest<V,T>(this, callable).callAsync(this);
        return new FutureAdapter<V,UserResponse<V,T>>(f) {
            protected V adapt(UserResponse<V,T> r) throws ExecutionException {
                try {
                    return r.retrieve(Channel.this, UserRequest.getClassLoader(callable));
                } catch (Throwable t) {// really means catch(T t)
                    throw new ExecutionException(t);
                }
            }
        };
    }

    /**
     * Aborts the connection in response to an error.
     *
     * This is an SPI for {@link CommandTransport} implementation to notify
     * {@link Channel} when the underlying connection is severed.
     *
     * @param e
     *      The error that caused the connection to be aborted. Never null.
     */
    @java.lang.SuppressWarnings("ToArrayCallWithZeroLengthArrayArgument")
    @SuppressWarnings("ITA_INEFFICIENT_TO_ARRAY") // intentionally; race condition on listeners otherwise
    public void terminate(@Nonnull IOException e) {
        try {
            synchronized (this) {
                if (e == null) throw new IllegalArgumentException();
                outClosed = inClosed = e;
                // we need to clear these out early in order to ensure that a GC operation while
                // proceding with the close does not result in a batch of UnexportCommand instances
                // being attempted to send over the locked and now closing channel.
                RemoteInvocationHandler.notifyChannelTermination(this);
                try {
                    transport.closeRead();
                } catch (IOException x) {
                    logger.log(Level.WARNING, "Failed to close down the reader side of the transport", x);
                }
                try {
                    synchronized (pendingCalls) {
                        for (Request<?, ?> req : pendingCalls.values())
                            req.abort(e);
                        pendingCalls.clear();
                    }
                    synchronized (executingCalls) {
                        for (Request<?, ?> r : executingCalls.values()) {
                            java.util.concurrent.Future<?> f = r.future;
                            if (f != null) f.cancel(true);
                        }
                        executingCalls.clear();
                    }
                    exportedObjects.abort(e);
                    // break any object cycles into simple chains to simplify work for the garbage collector
                    reference.clear(e);
                } finally {
                    notifyAll();
                }
            } // JENKINS-14909: leave synch block
        } finally {
            if (e instanceof OrderlyShutdown) e = null;
            for (Listener l : listeners.toArray(new Listener[0])) {
                try {
                    l.onClosed(this, e);
                } catch (Throwable t) {
                    LogRecord lr = new LogRecord(Level.SEVERE, "Listener {0} propagated an exception for channel {1}'s close: {2}");
                    lr.setThrown(t);
                    lr.setParameters(new Object[]{l, this, t.getMessage()});
                    logger.log(lr);
                }
            }
        }
    }

    /**
     * Registers a new {@link Listener}.
     *
     * @see #removeListener(Listener)
     */
    public void addListener(Listener l) {
        listeners.add(l);
    }

    /**
     * Removes a listener.
     *
     * @return
     *      false if the given listener has not been registered to begin with.
     */
    public boolean removeListener(Listener l) {
        return listeners.remove(l);
    }

    /**
     * Adds a {@link CallableFilter} that gets a chance to decorate every {@link Callable}s that run locally
     * sent by the other peer.
     *
     * This is useful to tweak the environment those closures are run, such as setting up the thread context
     * environment.
     */
    public void addLocalExecutionInterceptor(CallableDecorator decorator) {
        decorators.add(decorator);
    }

    /**
     * Removes the filter introduced by {@link #addLocalExecutionInterceptor(CallableFilter)}.
     */
    public void removeLocalExecutionInterceptor(CallableDecorator decorator) {
        decorators.remove(decorator);
    }

    /**
     * @deprecated
     *      use {@link #addLocalExecutionInterceptor(CallableDecorator)}
     */
    @Deprecated
    public void addLocalExecutionInterceptor(CallableFilter filter) {
        addLocalExecutionInterceptor(new CallableDecoratorAdapter(filter));
    }

    /**
     * @deprecated
     *      use {@link #removeLocalExecutionInterceptor(CallableDecorator)}
     */
    @Deprecated
    public void removeLocalExecutionInterceptor(CallableFilter filter) {
        removeLocalExecutionInterceptor(new CallableDecoratorAdapter(filter));
    }
    
    /**
     * Waits for this {@link Channel} to be closed down.
     *
     * The close-down of a {@link Channel} might be initiated locally or remotely.
     *
     * @throws InterruptedException
     *      If the current thread is interrupted while waiting for the completion.
     */
    public synchronized void join() throws InterruptedException {
        while(inClosed==null || outClosed==null)
            // not that I really encountered any situation where this happens, but
            // given tickets like JENKINS-20709 that talks about hangs, it seems
            // like a good defensive measure to periodically wake up to make sure
            // that the wait condition is still not met in case we don't call notifyAll correctly
            wait(30*1000);
    }

    /**
     * If the receiving end of the channel is closed (that is, if we are guaranteed to receive nothing further),
     * this method returns true.
     */
    public boolean isInClosed() {
        return inClosed!=null;
    }

    /**
     * Returns true if this channel has any of the security restrictions enabled.
     *
     * @deprecated
     *      Use methods like {@link #isRemoteClassLoadingAllowed()} and {@link #isArbitraryCallableAllowed()}
     *      to test individual features.
     */
    @Deprecated
    public boolean isRestricted() {
        return !isRemoteClassLoadingAllowed() || !isArbitraryCallableAllowed();
    }

    /**
     * Activates/disables all the security restriction mode.
     *
     * @deprecated
     *      Use methods like {@link #setRemoteClassLoadingAllowed(boolean)} and {@link #setArbitraryCallableAllowed(boolean)}
     *      to control individual features.
     */
    @Deprecated
    public void setRestricted(boolean b) {
        setRemoteClassLoadingAllowed(!b);
        setArbitraryCallableAllowed(!b);
    }

    /**
     * @since TODO
     */
    public boolean isRemoteClassLoadingAllowed() {
        return remoteClassLoadingAllowed;
    }

    /**
     * Controls whether or not this channel is willing to load classes from the other side.
     * The default is on.
     * @since TODO
     */
    public void setRemoteClassLoadingAllowed(boolean b) {
        this.remoteClassLoadingAllowed = b;
    }

    /**
     * @since TODO
     */
    public boolean isArbitraryCallableAllowed() {
        return arbitraryCallableAllowed;
    }

    /**
     * @see ChannelBuilder#withArbitraryCallableAllowed(boolean)
     * @since TODO
     */
    public void setArbitraryCallableAllowed(boolean b) {
        this.arbitraryCallableAllowed = b;
    }

    /**
     * Sets the maximum bytecode version (~ JDK) that we expect this channel to be able to load.
     * If attempts are made to load remote classes using newer bytecode, they are immediately rejected,
     * even if the remote JVM is actually new enough to load it.
     * This helps maintain compatibility by making tests fail immediately without the need for an old JDK installation.
     * By default, the remote class loader will try to load any bytecode version.
     * @param level e.g. 5 for JDK 5 (the minimum sensible value)
     * @since 2.29
     */
    public void setMaximumBytecodeLevel(short level) throws IOException, InterruptedException {
        if (level < 5) {
            throw new IllegalArgumentException("Does not make sense to specify JDK 1.4 or below since remoting itself requires JDK 5+");
        }
        call(new SetMaximumBytecodeLevel(level));
    }
    private static final class SetMaximumBytecodeLevel implements Callable<Void,RuntimeException> {
        private static final long serialVersionUID = 1;
        private final short level;
        SetMaximumBytecodeLevel(short level) {
            this.level = level;
        }
        public Void call() throws RuntimeException {
            Channel.current().maximumBytecodeLevel = level;
            return null;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
            // no specific role needed, which is somewhat dubious, but I can't think of any attack vector that involves this.
            // it would have been simpler if the setMaximumBytecodeLevel only controlled the local setting,
            // not the remote setting
        }
    }

    /**
     * Waits for this {@link Channel} to be closed down, but only up the given milliseconds.
     * @param timeout TImeout in milliseconds
     * @throws InterruptedException
     *      If the current thread is interrupted while waiting for the completion.
     * @since 1.299
     */
    public synchronized void join(long timeout) throws InterruptedException {
        long now = System.nanoTime();
        long end = now + TimeUnit.MILLISECONDS.toNanos(timeout);
        while ((end - now > 0L) && (inClosed == null || outClosed == null)) {
            wait(TimeUnit.NANOSECONDS.toMillis(end - now));
            now = System.nanoTime();
        }
    }

    /**
     * Notifies the remote peer that we are closing down.
     *
     * Execution of this command also triggers the {@code SynchronousCommandTransport.ReaderThread} to shut down
     * and quit. The {@code CloseCommand} is always the last command to be sent on
     * {@link ObjectOutputStream}, and it's the last command to be read.
     */
    private static final class CloseCommand extends Command {
        private CloseCommand(Channel channel, @CheckForNull Throwable cause) {
            super(channel, cause);
        }

        protected void execute(Channel channel) {
            try {
                channel.close();
                channel.terminate(new OrderlyShutdown(createdAt));
            } catch (IOException e) {
                logger.log(Level.SEVERE,"close command failed on "+channel.name,e);
                logger.log(Level.INFO,"close command created at",createdAt);
            }
        }

        @Override
        public String toString() {
            return "close";
        }

        // this value is compatible with remoting < 2.8. I made an incompatible change in 2.8 that got corrected in 2.11.
        static final long serialVersionUID = 972857271608138115L;
    }

    /**
     * Signals the orderly shutdown of the channel, but captures
     * where the termination was initiated as a nested exception.
     */
    private static final class OrderlyShutdown extends IOException {
        private OrderlyShutdown(Throwable cause) {
            super(cause.getMessage());
            initCause(cause);
        }
        private static final long serialVersionUID = 1L;
    }

    /**
     * Resets all the performance counters.
     */
    public void resetPerformanceCounters() {
        classLoadingCount.set(0);
        classLoadingTime.set(0);
        classLoadingPrefetchCacheCount.set(0);
        resourceLoadingCount.set(0);
        resourceLoadingTime.set(0);
    }

    /**
     * Print the performance counters.
     * @param w Output writer
     * @since 2.24
     */
    public void dumpPerformanceCounters(PrintWriter w) throws IOException {
        // locale fixed to English to get ',' for every 3 digits
        int l = classLoadingCount.get();
        int p = classLoadingPrefetchCacheCount.get();
        w.printf(Locale.ENGLISH, "Class loading count=%d%n", l);
        w.printf(Locale.ENGLISH, "Class loading prefetch hit=%s (%d%%)%n", p, p*100/l);
        w.printf(Locale.ENGLISH, "Class loading time=%,dms%n", classLoadingTime.get() / (1000 * 1000));
        w.printf(Locale.ENGLISH, "Resource loading count=%d%n", resourceLoadingCount.get());
        w.printf(Locale.ENGLISH, "Resource loading time=%,dms%n", resourceLoadingTime.get() / (1000 * 1000));
    }

    //TODO: Make public after merge into the master branch
    /**
     * Print the diagnostic information.
     *
     * <p>
     * Here's how you interpret these metrics:
     *
     * <dl>
     * <dt>Created
     * <dd>
     * When the channel was created, which is more or less the same thing as when the channel is connected.
     *
     * <dt>Commands sent
     * <dd>
     * Number of {@link Command} objects sent to the other side. More specifically, number of commands
     * successfully passed to {@link #transport}, which means data was written to socket with
     * {@link ClassicCommandTransport} but just buffered for write with {@link NioChannelHub}.
     *
     * If you have access to the remoting diagnostics
     * of the other side of the channel, then you can compare this with "commandsReceived" metrics of the other
     * side to see how many commands are in transit in transport. If {@code commandsSent==commandsReceived},
     * then no command is in transit.
     *
     * <dt>Commands received
     * <dd>
     * Number of {@link Command} objects received from the other side. More precisely, number
     * of commands reported from {@link #transport}. So for example, if data is still buffered somewhere
     * in the networking stack, it won't be counted here.
     *
     * <dt>Last command sent
     * <dd>
     * The timestamp in which the last command was sent to the other side. The timestamp in which
     * {@link #lastCommandSentAt} was updated.
     *
     * <dt>Last command received
     * <dd>
     * The timestamp in which the last command was sent to the other side. The timestamp in which
     * {@link #lastCommandReceivedAt} was updated.
     *
     * <dt>Pending outgoing calls
     * <dd>
     * Number of RPC calls (e.g., method call through a {@linkplain RemoteInvocationHandler proxy})
     * that are made but not returned yet. If you have the remoting diagnostics of the other side, you
     * can compare this number with "pending incoming calls" on the other side to see how many RPC
     * calls are executing vs in flight. "one side's incoming calls" &lt; "the other side's outgoing calls"
     * indicates some RPC requests or responses are passing through the network layer, and mismatch
     * between "# of commands sent" vs "# of commands received" can give some indication of whether
     * it is request or response that's in flight.
     *
     * <dt>Pending incoming calls
     * <dd>
     * The reverse of "pending outgoing calls".
     * Number of RPC calls (e.g., method call through a {@linkplain RemoteInvocationHandler proxy})
     * that the other side has made to us but not yet returned yet.
     * </dl>
     * @param w Output destination
     * @throws IOException Error while creating or writing the channel information
     * 
     * @since 2.62.3 - stable 2.x (restricted)
     * @since TODO 3.x - public version 
     */
    @Restricted(NoExternalUse.class)
    public void dumpDiagnostics(@Nonnull PrintWriter w) throws IOException {
        w.printf("Channel %s%n",name);
        w.printf("  Created=%s%n", new Date(createdAt));
        w.printf("  Commands sent=%d%n", commandsSent);
        w.printf("  Commands received=%d%n", commandsReceived);
        w.printf("  Last command sent=%s%n", new Date(lastCommandSentAt));
        w.printf("  Last command received=%s%n", new Date(lastCommandReceivedAt));
        
        // TODO: Update after the merge to 3.x branch, where the Hashtable is going to be replaced as a part of
        // https://github.com/jenkinsci/remoting/pull/109
        w.printf("  Pending calls=%d%n", pendingCalls.size());
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void close() throws IOException {
        close(null);
    }

    /**
     * Closes the channel.
     *
     * @param diagnosis
     *      If someone (either this side or the other side) tries to use a channel that's already closed,
     *      they'll get a stack trace indicating that the channel has already been closed. This diagnosis,
     *      if provided, will further chained to that exception, providing more contextual information
     *      about why the channel was closed.
     *
     * @since 2.8
     */
    public synchronized void close(@CheckForNull Throwable diagnosis) throws IOException {
        if(outClosed!=null)  return;  // already closed

        try {
            send(new CloseCommand(this, diagnosis));
        } catch (ChannelClosedException e) {
            logger.log(Level.FINEST, "Channel is already closed", e);
            terminate(e);
            return;
        } catch (IOException e) {
            // send should only ever - worst case - throw an IOException so we'll just catch that and not Throwable
            logger.log(Level.WARNING, "Having to terminate early", e);
            terminate(e);
            return;
        }
        outClosed = new IOException().initCause(diagnosis);   // last command sent. no further command allowed. lock guarantees that no command will slip inbetween
        notifyAll();
        try {
            transport.closeWrite();
        } catch (IOException e) {
            // there's a race condition here.
            // the remote peer might have already responded to the close command
            // and closed the connection, in which case our close invocation
            // could fail with errors like
            // "java.io.IOException: The pipe is being closed"
            // so let's ignore this error.
        }

        // termination is done by CloseCommand when we received it.
    }

    /**
     * Gets the application specific property set by {@link #setProperty(Object, Object)}.
     * These properties are also accessible from the remote channel via {@link #getRemoteProperty(Object)}.
     *
     * <p>
     * This mechanism can be used for one side to discover contextual objects created by the other JVM
     * (as opposed to executing {@link Callable}, which cannot have any reference to the context
     * of the remote {@link Channel}.
     */
    public Object getProperty(Object key) {
        return properties.get(key);
    }

    public <T> T getProperty(ChannelProperty<T> key) {
        return key.type.cast(getProperty((Object) key));
    }

    /**
     * Works like {@link #getProperty(Object)} but wait until some value is set by someone.
     *
     * @throws IllegalStateException
     *      if the channel is closed. The idea is that channel properties are expected to be the coordination
     *      mechanism between two sides of the channel, and this method in particular is a way of one side
     *      to wait for the set by the other side of the channel (via {@link #waitForRemoteProperty(Object)}.
     *      If we don't abort after the channel shutdown, this method will block forever.
     */
    public synchronized Object waitForProperty(Object key) throws InterruptedException {
        while(true) {
            Object v = properties.get(key);
            if(v!=null) return v;

            if (isInClosed())
                throw (IllegalStateException)new IllegalStateException("Channel was already closed").initCause(inClosed);
            if (isOutClosed())
                throw (IllegalStateException)new IllegalStateException("Channel was already closed").initCause(outClosed);

            wait();
        }
    }

    public <T> T waitForProperty(ChannelProperty<T> key) throws InterruptedException {
        return key.type.cast(waitForProperty((Object) key));
    }

    /**
     * Sets the property value on this side of the channel.
     * 
     * @see #getProperty(Object)
     */
    public synchronized Object setProperty(Object key, Object value) {
        Object old = value!=null ? properties.put(key, value) : properties.remove(key);
        notifyAll();
        return old;
    }

    public <T> T setProperty(ChannelProperty<T> key, T value) {
        return key.type.cast(setProperty((Object) key, value));
    }


    /**
     * Gets the property set on the remote peer.
     *
     * @return {@code null}
     *      if the property of the said key isn't set.
     */
    @CheckForNull
    public Object getRemoteProperty(Object key) {
        return remoteChannel.getProperty(key);
    }

    @CheckForNull
    public <T> T getRemoteProperty(ChannelProperty<T> key) {
        return key.type.cast(getRemoteProperty((Object) key));
    }

    /**
     * Gets the property set on the remote peer.
     * This method blocks until the property is set by the remote peer.
     */
    public Object waitForRemoteProperty(Object key) throws InterruptedException {
        return remoteChannel.waitForProperty(key);
    }

    /**
     * @deprecated
     *      Because {@link ChannelProperty} is identity-equality, this method would never work.
     *      This is a design error.
     */
    @Deprecated
    public <T> T waitForRemoteProperty(ChannelProperty<T> key) throws InterruptedException {
        return key.type.cast(waitForRemoteProperty((Object) key));
    }

    /**
     * Obtain the output stream passed to the constructor.
     *
     * @deprecated
     *      Future version of the remoting module may add other modes of creating channel
     *      that doesn't involve stream pair. Therefore, we aren't committing to this method.
     *      This method isn't a part of the committed API of the channel class.
     * @return
     *      While the current version always return a non-null value, the caller must not
     *      make that assumption for the above reason. This method may return null in the future version
     *      to indicate that the {@link Channel} is not sitting on top of a stream pair.
     */
    @Deprecated
    public OutputStream getUnderlyingOutput() {
        return underlyingOutput;
    }

    /**
     * Starts a local to remote port forwarding (the equivalent of "ssh -L").
     *
     * @param recvPort
     *      The port on this local machine that we'll listen to. 0 to let
     *      OS pick a random available port. If you specify 0, use
     *      {@link ListeningPort#getPort()} to figure out the actual assigned port. 
     * @param forwardHost
     *      The remote host that the connection will be forwarded to.
     *      Connection to this host will be made from the other JVM that
     *      this {@link Channel} represents.
     * @param forwardPort
     *      The remote port that the connection will be forwarded to.
     * @return
     */
    public ListeningPort createLocalToRemotePortForwarding(int recvPort, String forwardHost, int forwardPort) throws IOException, InterruptedException {
        PortForwarder portForwarder = new PortForwarder(recvPort,
                ForwarderFactory.create(this, forwardHost, forwardPort));
        portForwarder.start();
        return portForwarder;
    }

    /**
     * Starts a remote to local port forwarding (the equivalent of "ssh -R").
     *
     * @param recvPort
     *      The port on the remote JVM (represented by this {@link Channel})
     *      that we'll listen to. 0 to let
     *      OS pick a random available port. If you specify 0, use
     *      {@link ListeningPort#getPort()} to figure out the actual assigned port.
     * @param forwardHost
     *      The remote host that the connection will be forwarded to.
     *      Connection to this host will be made from this JVM.
     * @param forwardPort
     *      The remote port that the connection will be forwarded to.
     * @return
     */
    public ListeningPort createRemoteToLocalPortForwarding(int recvPort, String forwardHost, int forwardPort) throws IOException, InterruptedException {
        return PortForwarder.create(this,recvPort,
                ForwarderFactory.create(forwardHost, forwardPort));
    }

    /**
     * Dispenses an unique I/O ID.
     *
     * When a {@link Channel} requests an activity that happens in {@link #pipeWriter},
     * the sender assigns unique I/O ID to this request, which enables later
     * commands to sync up with their executions.
     *
     * @see PipeWriter
     */
    /*package*/ int newIoId() {
        int v = ioId.incrementAndGet();
        lastIoId.get()[0] = v;
        return v;
    }

    /**
     * Gets the last I/O ID issued by the calling thread, or 0 if none is recorded.
     */
    /*package*/ int lastIoId() {
        return lastIoId.get()[0];
    }

    /**
     * Blocks until all the I/O packets sent before this gets fully executed by the remote side, then return.
     *
     * @throws IOException
     *      If the remote doesn't support this operation, or if sync fails for other reasons.
     */
    public void syncIO() throws IOException, InterruptedException {
        call(new IOSyncer());
    }

//  Barrier doesn't work because IOSyncer is a Callable and not Command
//  (yet making it Command would break JENKINS-5977, which introduced this split in the first place!)
//    /**
//     * Non-blocking version of {@link #syncIO()} that has a weaker commitment.
//     *
//     * This method only guarantees that any later remote commands will happen after all the I/O packets sent before
//     * this method call gets fully executed. This is faster in that it it doesn't wait for a response
//     * from the other side, yet it normally achieves the desired semantics.
//     */
//    public void barrierIO() throws IOException {
//        callAsync(new IOSyncer());
//    }

    public void syncLocalIO() throws InterruptedException {
        Thread t = Thread.currentThread();
        String old = t.getName();
        t.setName("I/O sync: "+old);
        try {
            // no one waits for the completion of this Runnable, so not using I/O ID
            pipeWriter.submit(0,new Runnable() {
                public void run() {
                    // noop
                }
            }).get();
        } catch (ExecutionException e) {
            throw new AssertionError(e); // impossible
        } finally {
            t.setName(old);
        }
    }

    private static final class IOSyncer implements Callable<Object, InterruptedException> {
        public Object call() throws InterruptedException {
            Channel.current().syncLocalIO();
            return null;
        }

        /**
         * This callable is needed for the proper operation of pipes.
         * In the worst case it causes a bit of wasted CPU cycles without any side-effect,
         * and one can always refuse to read/write from/to pipe, so this layer need not provide
         * any security.
         */
        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * Decorates the stack elements of the given {@link Throwable} by adding the call site information.
     */
    /*package*/ void attachCallSiteStackTrace(Throwable t) {
        Exception e = new Exception();
        StackTraceElement[] callSite = e.getStackTrace();
        StackTraceElement[] original = t.getStackTrace();

        StackTraceElement[] combined = new StackTraceElement[original.length+1+callSite.length];
        System.arraycopy(original,0,combined,0,original.length); // original stack trace at the top
        combined[original.length] = new StackTraceElement(".....","remote call to "+name,null,-2);
        System.arraycopy(callSite,0,combined,original.length+1,callSite.length);

        t.setStackTrace(combined);
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return super.toString()+":"+name;
    }

    /**
     * Dumps the list of exported objects and their allocation traces to the given output.
     */
    public void dumpExportTable(PrintWriter w) throws IOException {
        exportedObjects.dump(w);
    }

    public ExportTable.ExportList startExportRecording() {
        return exportedObjects.startRecording();
    }

    /**
     * TODO: this is not safe against clock skew and is called from jenkins core (and potentially plugins)
     * @see #lastCommandReceivedAt
     */
    public long getLastHeard() {
        // TODO - this is not safe against clock skew and is called from jenkins core (and potentially plugins)
        return lastCommandReceivedAt;
    }

    /*package*/ static Channel setCurrent(Channel channel) {
        Channel old = CURRENT.get();
        if (channel == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(channel);
        }
        return old;
    }

    /**
     * This method can be invoked during the serialization/deserialization of
     * objects when they are transferred to the remote {@link Channel},
     * as well as during {@link Callable#call()} is invoked. 
     *
     * @return {@code null}
     *      if the calling thread is not performing serialization.
     */
    @CheckForNull
    public static Channel current() {
        return CURRENT.get();
    }

    // TODO: Unrestrict after the merge into the master.
    // By now one may use it via the reflection logic only
    /**
     * Calls {@link #dumpDiagnostics(PrintWriter)} across all the active channels in this system.
     * Used for diagnostics.
     *
     * @param w Output destination
     * 
     * @since 2.62.3 - stable 2.x (restricted)
     * @since TODO 3.x - public version 
     */
    @Restricted(NoExternalUse.class)
    public static void dumpDiagnosticsForAll(@Nonnull PrintWriter w) {
        final Ref[] channels = ACTIVE_CHANNELS.values().toArray(new Ref[0]);
        int processedCount = 0;
        for (Ref ref : channels) {
            // Check if we can still write the output
            if (w.checkError()) {
                logger.log(Level.WARNING, 
                        String.format("Cannot dump diagnostics for all channels, because output stream encountered an error. "
                                + "Processed %d of %d channels, first unprocessed channel reference is %s.",
                                processedCount, channels.length, ref
                        )); 
                break;
            }
            
            // Dump channel info if the reference still points to it
            final Channel ch = ref.channel();
            if (ch != null) {
                try {
                    ch.dumpDiagnostics(w);
                } catch (Throwable ex) {
                    if (ex instanceof Error) {
                        throw (Error)ex;
                    }
                    w.printf("Cannot dump diagnostics for the channel %s. %s. See Error stacktrace in system logs", 
                            ch.getName(), ex.getMessage());
                    logger.log(Level.WARNING, 
                            String.format("Cannot dump diagnostics for the channel %s", ch.getName()), ex);
                }
            }
            processedCount++;
        }
    }

    /**
     * Remembers the current "channel" associated for this thread.
     */
    private static final ThreadLocal<Channel> CURRENT = new ThreadLocal<Channel>();

    private static final Logger logger = Logger.getLogger(Channel.class.getName());

    /**
     * Default pipe window size.
     *
     * <p>
     * This controls the amount of bytes that can be in flight. Value too small would fail to efficiently utilize
     * a high-latency/large-bandwidth network, but a value too large would cause the risk of a large memory consumption
     * when a pipe clogs (that is, the receiver isn't consuming bytes we are sending fast enough.)
     *
     * <p>
     * If we have a gigabit ethernet (with effective transfer rate of 100M bps) and 20ms latency, the pipe will hold
     * (100M bits/sec * 0.02sec / 8 bits/byte = 0.25MB. So 1MB or so is big enough for most network, and hopefully
     * this is an acceptable enough memory consumption in case of clogging.
     *
     * @see PipeWindow
     */
    public static final int PIPE_WINDOW_SIZE = Integer.getInteger(Channel.class.getName()+".pipeWindowSize",1024*1024);

    /**
     * Keep track of active channels in the system for diagnostics purposes.
     */
    private static final Map<Channel,Ref> ACTIVE_CHANNELS = Collections.synchronizedMap(new WeakHashMap<Channel, Ref>());

    static final Class jarLoaderProxy;

    static {
        // dead-lock prevention.
        //
        // creating a new proxy class is a classloading activity, so it can create a dead-lock situation
        // if thread A starts classloading via RemoteClassLoader.loadClass(),
        // then thread B use JarCacheSupport.prefetch and tries to create a proxy for JarLoader
        //    (which blocks as Proxy.getProxyClass waits for RemoteClassLoader.defineClass lock by thread A)
        // then thread A tries to touch JarLoader proxy (which blocks on thread B)
        //
        // to avoid situations like this, create proxy classes that we need during the classloading
        jarLoaderProxy=RemoteInvocationHandler.getProxyClass(JarLoader.class);
    }

    /**
     * Do not use an anonymous inner class as that can cause a {@code this} reference to escape.
     */
    private static class ThreadLastIoId extends ThreadLocal<int[]> {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    }

    /**
     * A reference for the {@link Channel} that can be cleared out on {@link #close()}/{@link #terminate(IOException)}.
     * Could probably be replaced with {@link AtomicReference} but then we would not retain the only change being
     * from valid channel to {@code null} channel semantics of this class.
     * @since 2.52
     * @see #reference
     */
    /*package*/ static final class Ref {
        
        /**
         * Cached name of the channel.
         * @see {@link Channel#getName()}
         */
        @Nonnull
        private final String name;

        /**
         * The channel.
         */
        @CheckForNull
        private Channel channel;

        /**
         * If the channel is cleared, retain the reason channel was closed to assist diagnostics.
         */
        private Exception cause;

        /**
         * Constructor.
         * @param channel the {@link Channel}.
         */
        private Ref(@CheckForNull Channel channel) {
            this.name = channel != null ? channel.getName() : "unknown (null reference)";
            this.channel = channel;
        }

        /**
         * Returns the {@link Channel} or {@code null} if the the {@link Channel} has been closed/terminated.
         * @return the {@link Channel} or {@code null} 
         */
        @CheckForNull
        public Channel channel() {
            return channel;
        }

        /**
         * If the channel is null, return the cause of the channel termination.
         * @return Cause or {@code null} if it is not available
         * @since 3.1
         */
        @CheckForNull
        public Exception cause() {
            return  cause;
        }

        /**
         * Returns the cached name of the channel.
         * @return Channel name or {@code "unknown (null reference)"} if the reference has been initialized by {@code null} {@link Channel} instance.
         * @see Channel#getName()
         * @since 3.1
         */
        @Nonnull
        public String name() {
            return name;
        }

        /**
         * Clears the {@link #channel} to signify that the {@link Channel} has been closed and break any complex
         * object cycles that might prevent the full garbage collection of the channel's associated object tree.
         * @param cause Channel termination cause
         */
        public void clear(@Nonnull Exception cause) {
            this.channel = null;
            this.cause = cause;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean equals(Object o) {
            // compare based on instance identity
            return this == o;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("Channel.Ref{");
            sb.append("channel=").append(channel);
            sb.append(",name=").append(name);
            sb.append('}');
            return sb.toString();
        }
    }
}
