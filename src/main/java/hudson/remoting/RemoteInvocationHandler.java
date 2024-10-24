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
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sits behind a proxy object and implements the proxy logic.
 *
 * @author Kohsuke Kawaguchi
 */
// TODO: Likely should be serializable over Remoting logic, but this class has protection logic
// Use-cases need to be investigated
@SuppressFBWarnings(value = "DESERIALIZATION_GADGET", justification = "This class has protection logic.")
final class RemoteInvocationHandler implements InvocationHandler, Serializable {
    /**
     * Our logger.
     */
    private static final Logger logger = Logger.getLogger(RemoteInvocationHandler.class.getName());
    /**
     * The {@link Unexporter} to track {@link RemoteInvocationHandler} instances that should be unexported when
     * collected by the garbage collector.
     * @since 2.52
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
     * @since 2.52
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
    private final @CheckForNull Throwable origin;

    /**
     * Indicates that the handler operates in the user space.
     * In such case the requests will be automatically failed if the
     * Remoting channel is not fully operational.
     */
    private final boolean userSpace;

    /** @see Command#Command(boolean) */
    private final boolean recordCreatedAt;

    /**
     * Creates a proxy that wraps an existing OID on the remote.
     */
    private RemoteInvocationHandler(
            Channel channel,
            int id,
            boolean userProxy,
            boolean autoUnexportByCaller,
            boolean userSpace,
            Class<?> proxyType,
            boolean recordCreatedAt) {
        this.channel = channel == null ? null : channel.ref();
        this.oid = id;
        this.userProxy = userProxy;
        this.origin = recordCreatedAt ? new Exception("Proxy " + this + " was created for " + proxyType) : null;
        this.autoUnexportByCaller = autoUnexportByCaller;
        this.userSpace = userSpace;
        this.recordCreatedAt = recordCreatedAt;
    }

    /**
     * Wraps an OID to the typed wrapper.
     *
     * @param userProxy If {@code true} (recommended), all commands will be wrapped into {@link UserRequest}s.
     * @param userSpace If {@code true} (recommended), the requests will be executed in a user scope
     * @param recordCreatedAt as in {@link Command#Command(boolean)}
     */
    @NonNull
    static <T> T wrap(
            Channel channel,
            int id,
            Class<T> type,
            boolean userProxy,
            boolean autoUnexportByCaller,
            boolean userSpace,
            boolean recordCreatedAt) {
        ClassLoader cl = type.getClassLoader();
        // if the type is a JDK-defined type, classloader should be for IReadResolve
        if (cl == null || cl == ClassLoader.getSystemClassLoader()) {
            cl = IReadResolve.class.getClassLoader();
        }
        RemoteInvocationHandler handler = new RemoteInvocationHandler(
                channel, id, userProxy, autoUnexportByCaller, userSpace, type, recordCreatedAt);
        if (channel != null) {
            if (!autoUnexportByCaller) {
                UNEXPORTER.watch(handler);
            }
        }
        return type.cast(Proxy.newProxyInstance(cl, new Class[] {type, IReadResolve.class}, handler));
    }

    /**
     * Called as soon as a channel is terminated, we cannot use {@link Channel.Listener} as that is called after
     * termination has completed and we need to clean up the {@link PhantomReferenceImpl} before they try to
     * clean themselves up by sending the {@link UnexportCommand} over the closing {@link Channel}.
     *
     * @param channel the {@link Channel} that is terminating/terminated.
     * @since 2.52
     */
    /*package*/ static void notifyChannelTermination(Channel channel) {
        UNEXPORTER.onChannelTermination(channel);
    }

    /*package*/ static Class<?> getProxyClass(Class<?> type) {
        return Proxy.getProxyClass(type.getClassLoader(), type, IReadResolve.class);
    }

    /**
     * Returns the backing channel or {@code null} if the channel is disconnected or otherwise unavailable.
     *
     * @return the backing channel or {@code null}.
     * @since 2.52
     */
    @CheckForNull
    private Channel channel() {
        final Channel.Ref ch = this.channel;
        return ch == null ? null : ch.channel();
    }

    /**
     * Returns the backing channel or throws an {@link IOException} if the channel is disconnected or
     * otherwise unavailable.
     *
     * @return the backing channel.
     * @throws IOException if the channel is disconnected or otherwise unavailable.
     * @since 2.52
     */
    @NonNull
    private Channel channelOrFail() throws IOException {
        final Channel.Ref ch = this.channel;
        if (ch == null) {
            throw new IOException("Not connected to any channel");
        }
        Channel c = ch.channel();
        if (c == null) {
            throw new IOException("Backing channel '" + ch.name() + "' is disconnected.", ch.cause());
        }
        return c;
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
            if (rih.channel() == src) {
                return rih.oid;
            }
        }
        return -1;
    }

    /**
     * If the given object is a proxy object, return the {@link Channel}
     * object that it's associated with. Otherwise null.
     */
    @CheckForNull
    public static Channel unwrap(Object proxy) {
        InvocationHandler h = Proxy.getInvocationHandler(proxy);
        if (h instanceof RemoteInvocationHandler) {
            RemoteInvocationHandler rih = (RemoteInvocationHandler) h;
            return rih.channel();
        }
        return null;
    }

    @Override
    @Nullable
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == IReadResolve.class) {
            // readResolve on the proxy.
            // if we are going back to where we came from, replace the proxy by the real object
            if (goingHome) {
                return Channel.currentOrFail().getExportedObject(oid);
            } else {
                return proxy;
            }
        }

        if (channel == null) {
            throw new IllegalStateException("proxy is not connected to a channel");
        }

        if (args == null) {
            args = EMPTY_ARRAY;
        }

        Class<?> dc = method.getDeclaringClass();
        if (dc == Object.class) {
            // handle equals and hashCode by ourselves
            try {
                return method.invoke(this, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        // delegate the rest of the methods to the remote object

        boolean async = method.isAnnotationPresent(Asynchronous.class);
        RPCRequest req = userSpace
                ? new UserRPCRequest(oid, method, args, userProxy ? dc.getClassLoader() : null, recordCreatedAt)
                : new RPCRequest(oid, method, args, userProxy ? dc.getClassLoader() : null, recordCreatedAt);
        try {
            if (userProxy) {
                if (async) {
                    channelOrFail().callAsync(req);
                } else {
                    return channelOrFail().call(req);
                }
            } else {
                if (async) {
                    req.callAsync(channelOrFail());
                } else {
                    return req.call(channelOrFail());
                }
            }
            return null;
        } catch (Throwable e) {
            for (Class<?> exc : method.getExceptionTypes()) {
                if (exc.isInstance(e)) {
                    throw e; // signature explicitly lists this exception
                }
            }
            if (e instanceof RuntimeException || e instanceof Error) {
                throw e; // these can be thrown from any methods
            }

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
        goingHome = channel != null;
        oos.defaultWriteObject();
    }

    /**
     * Two proxies are the same iff they represent the same remote object.
     */
    @Override
    public boolean equals(Object o) {
        if (o != null && Proxy.isProxyClass(o.getClass())) {
            o = Proxy.getInvocationHandler(o);
        }

        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        RemoteInvocationHandler that = (RemoteInvocationHandler) o;

        return this.oid == that.oid && this.channel == that.channel;
    }

    @Override
    public int hashCode() {
        return oid;
    }

    /**
     * Finalizers are run only under extreme GC pressure whereas {@link PhantomReference} are cleared out
     * more quickly, thus we use a {@link PhantomReference} in place of the override of {@link Object#finalize()}
     * that was previously used in order to unexport.
     * @since 2.52
     */
    private static class PhantomReferenceImpl extends PhantomReference<RemoteInvocationHandler> {

        /**
         * The object id to unexport.
         */
        private final int oid;
        /**
         * The origin from where the object was created.
         */
        private @CheckForNull Throwable origin;
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
        private PhantomReferenceImpl(
                RemoteInvocationHandler referent, ReferenceQueue<? super RemoteInvocationHandler> referenceQueue) {
            super(referent, referenceQueue);
            this.oid = referent.oid;
            this.origin = Unexporter.retainOrigin ? referent.origin : null;
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
     * @since 2.52
     */
    private static class Unexporter implements Runnable {

        /**
         * Constant to help conversion
         */
        private static final long NANOSECONDS_PER_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1L);
        /**
         * Constant to help conversion
         */
        private static final double NANOSECONDS_PER_SECOND = TimeUnit.SECONDS.toNanos(1L);
        /**
         * If you have a high throughput of remoting requests and you do not need the call-site tracability
         * you can reduce GC pressure by discarding the origin call-site stack traces and setting this system
         * property.
         * @since 2.58
         */
        private static final boolean retainOrigin =
                Boolean.parseBoolean(System.getProperty(Unexporter.class.getName() + ".retainOrigin", "true"));
        /**
         * How often to sweep out references from {@link Channel} instances that are closed (and therefore have a no-op
         * {@link PhantomReferenceImpl#cleanup()}.
         * @since 2.58
         */
        private static final long sweepInterval =
                secSysPropAsNanos(Unexporter.class.getName() + ".sweepInterval", 0.1, 0.2, 5.0);
        /**
         * How often to measure the quantity of work being done by the {@link Unexporter}.
         *
         * @since 2.58
         */
        private static final long measureInterval =
                secSysPropAsNanos(Unexporter.class.getName() + ".measureInterval", 5.0, 5.0, 60.0);
        /**
         * How often to report the statistics for the work being done by the {@link Unexporter}.
         *
         * @since 2.58
         */
        private static final long reportInterval =
                secSysPropAsNanos(Unexporter.class.getName() + ".reportInterval", 30.0, 60.0, 3600.0);
        /**
         * Stress testing has revealed that it is better to batch removing references and processing their clean-up
         * even if this means that the sweep gets delayed. This constant allows for tuning of the batch size,
         * if the current recommendation proves insufficient in real world scenarios.
         */
        private static final int batchSize =
                Math.max(10, Math.min(10000, Integer.getInteger(Unexporter.class.getName() + ".batchSize", 256)));
        /**
         * The decay factor for a rolling average that expects to be updated every {@link #measureInterval} and
         * should have a lifetime of approx 1 minute.
         */
        private static final double m1Alpha = 1.0 - Math.exp(-measureInterval * 1.0 / TimeUnit.MINUTES.toNanos(1));
        /**
         * The decay factor for a rolling average that expects to be updated every {@link #measureInterval} and
         * should have a lifetime of approx 5 minutes.
         */
        private static final double m5Alpha = 1.0 - Math.exp(-measureInterval * 1.0 / TimeUnit.MINUTES.toNanos(5));
        /**
         * The decay factor for a rolling average that expects to be updated every {@link #measureInterval} and
         * should have a lifetime of approx 15 minutes.
         */
        private static final double m15Alpha = 1.0 - Math.exp(-measureInterval * 1.0 / TimeUnit.MINUTES.toNanos(15));
        /**
         * Our executor service, we use at most one thread for all {@link Channel} instances in the current classloader.
         */
        private final ExecutorService svc = new AtmostOneThreadExecutor(
                new NamingThreadFactory(new DaemonThreadFactory(), RemoteInvocationHandler.class.getSimpleName()));
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
        private final ReferenceQueue<? super RemoteInvocationHandler> queue = new ReferenceQueue<>();
        /**
         * The "live" {@link PhantomReferenceImpl} instances for each active {@link Channel}.
         */
        private final ConcurrentMap<Channel.Ref, List<PhantomReferenceImpl>> referenceLists = new ConcurrentHashMap<>();
        /**
         * The 1 minute rolling average.
         */
        private double m1Avg = 0.0;
        /**
         * The 1 minute rolling variance.
         */
        private double m1Var = 0.0;
        /**
         * The 5 minute rolling average.
         */
        private double m5Avg = 0.0;
        /**
         * The 5 minute rolling variance.
         */
        private double m5Var = 0.0;
        /**
         * The 15 minute rolling average.
         */
        private double m15Avg = 0.0;
        /**
         * The 15 minute rolling variance.
         */
        private double m15Var = 0.0;
        /**
         * The cumulative all-time average.
         */
        private double tAvg = 0.0;
        /**
         * The cumulative number of measurements.
         */
        private long tCount = 0;
        /**
         * The cumulative all-time variance multiplied by the {@link #tCount}. It needs to be this way to minimize
         * the iterative updating cost while maximizing the precision.
         */
        private double tVarTimesCount = 0.0;
        /**
         * When we started the current measurement.
         */
        private long countStart = System.nanoTime();
        /**
         * The current measurement.
         */
        private long count = 0;
        /**
         * When we would like to complete the current measurement.
         */
        private long nextMeasure = countStart + measureInterval;
        /**
         * When we would like to report the statistics.
         */
        private long nextReport = countStart + reportInterval;

        /**
         * Gets the named system property value which is treated as a number of seconds and converted into nanoseconds.
         * The value can be specified using decimals which allows the user to specify meaningful times in a consistent
         * unit rather than force them to jump between different units.
         *
         * @param name the name of the system property.
         * @param min the minimum (in seconds) to return.
         * @param def the default (in seconds) to return.
         * @param max the maximum (in seconds) to return.
         * @return the value of the system property after converting into nanoseconds.
         */
        private static long secSysPropAsNanos(String name, double min, double def, double max) {
            String value = System.getProperty(name);
            double seconds;
            try {
                seconds = value == null || value.isEmpty() ? def : Double.parseDouble(value);
            } catch (NumberFormatException e) {
                logger.log(
                        Level.WARNING,
                        String.format("The system property '%s'='%s' could not be parsed", name, value),
                        e);
                seconds = def;
            }
            return (long) (1.0e9 * Math.max(min, Math.min(max, seconds)));
        }

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
                long nextSweep = System.nanoTime() + sweepInterval;
                PhantomReferenceImpl[] batch = new PhantomReferenceImpl[batchSize];
                while (!referenceLists.isEmpty()) {
                    if (System.nanoTime() - nextMeasure > 0) {
                        updateStats();
                    }
                    if (System.nanoTime() - nextReport > 0) {
                        reportStats();
                    }
                    try {
                        long remaining;
                        int batchIndex = 0;
                        while (nextSweep - System.nanoTime() > 0) {
                            while (batchIndex < batch.length
                                    && (remaining = (nextSweep - System.nanoTime()) / NANOSECONDS_PER_MILLISECOND)
                                            > 0) {
                                Reference<?> ref = queue.remove(remaining);
                                if (ref == null) {
                                    break;
                                }
                                if (ref instanceof PhantomReferenceImpl) {
                                    batch[batchIndex++] = (PhantomReferenceImpl) ref;
                                }
                            }
                            for (int index = 0; index < batchIndex; index++) {
                                count++;
                                final Channel.Ref channelRef = batch[index].channel;
                                try {
                                    batch[index].cleanup();
                                } catch (ChannelClosedException e) {
                                    // ignore, the cleanup is a no-op
                                } catch (Error e) {
                                    logger.log(
                                            Level.SEVERE,
                                            String.format(
                                                    "Couldn't clean up oid=%d from %s",
                                                    batch[index].oid, batch[index].origin),
                                            e);
                                    throw e; // pass on as there is nothing we can do with an error
                                } catch (Throwable e) {
                                    logger.log(
                                            Level.WARNING,
                                            String.format(
                                                    "Couldn't clean up oid=%d from %s",
                                                    batch[index].oid, batch[index].origin),
                                            e);
                                } finally {
                                    if (channelRef != null) {
                                        final List<PhantomReferenceImpl> referenceList = referenceLists.get(channelRef);
                                        if (referenceList != null) {
                                            if (channelRef.channel() == null) {
                                                cleanList(referenceList);
                                            } else {
                                                referenceList.remove(batch[index]);
                                            }
                                        }
                                    }
                                    batch[index] = null; // clear out the reference from the array as we reuse the array
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        logger.log(Level.FINE, "Interrupted", e);
                    }
                    if (System.nanoTime() - nextSweep > 0) {
                        nextSweep = System.nanoTime() + sweepInterval;
                        // purge any dead channels, it does not matter if we spend a long time here as we are
                        // removing future potential work for us from ever entering the queue and freeing garbage
                        for (Iterator<Map.Entry<Channel.Ref, List<PhantomReferenceImpl>>> iterator =
                                        referenceLists.entrySet().iterator();
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
                }
            } finally {
                isAlive.set(false);
            }
        }

        private void updateStats() {
            long measureDuration = System.nanoTime() - countStart;
            double instantRate = count * NANOSECONDS_PER_SECOND / measureDuration;
            countStart = System.nanoTime();
            nextMeasure = countStart + measureInterval;
            count = 0;
            if (tCount == 0) {
                m1Avg = m5Avg = m15Avg = tAvg = instantRate;
                tCount = 1;
            } else {
                // compute the exponentially weighted moving average and variance
                // see http://www-uxsup.csx.cam.ac.uk/~fanf2/hermes/doc/antiforgery/stats.pdf
                double diff = instantRate - m1Avg;
                double incr = m1Alpha * diff;
                m1Avg = m1Avg + incr;
                m1Var = (1 - m1Alpha) * (m1Var + diff * incr);
                diff = instantRate - m5Avg;
                incr = m5Alpha * diff;
                m5Avg = m5Avg + incr;
                m5Var = (1 - m5Alpha) * (m5Var + diff * incr);
                diff = instantRate - m15Avg;
                incr = m15Alpha * diff;
                m15Avg = m15Avg + incr;
                m15Var = (1 - m15Alpha) * (m15Var + diff * incr);
                diff = instantRate - tAvg;
                tAvg = (tAvg * tCount + instantRate) / (++tCount);
                tVarTimesCount = tVarTimesCount + diff * (instantRate - tAvg);
            }
        }

        private void reportStats() {
            nextReport = System.nanoTime() + reportInterval;
            double m1Std = m1Var <= 0 ? 0 : Math.sqrt(m1Var);
            double m5Std = m5Var <= 0 ? 0 : Math.sqrt(m5Var);
            double m15Std = m15Var <= 0 ? 0 : Math.sqrt(m15Var);
            double tStd = tCount <= 0 || tVarTimesCount < 0 ? 0 : Math.sqrt(tVarTimesCount / tCount);
            Level targetLevel = m15Avg > 100 ? Level.INFO : m15Avg > 50 ? Level.FINE : Level.FINER;
            if (logger.isLoggable(targetLevel)) {
                logger.log(
                        targetLevel,
                        () -> String.format(
                                "rate(1min) = %.1f±%.1f/sec; "
                                        + "rate(5min) = %.1f±%.1f/sec; "
                                        + "rate(15min) = %.1f±%.1f/sec; "
                                        + "rate(total) = %.1f±%.1f/sec; N = %d",
                                m1Avg, m1Std, m5Avg, m5Std, m15Avg, m15Std, tAvg, tStd, tCount));
            }
            if (tCount < 10L) {
                // less than 10 reports is too soon to start alerting the user
                return;
            }
            // now test if the average is above 100/sec
            if (m15Std > 1 && 100 < m15Avg - 2 * m15Std) {
                if (tStd > 1 && 100 < tAvg - 2 * tStd) {
                    logger.log(
                            Level.SEVERE,
                            String.format(
                                    retainOrigin
                                            ? "The all time average rate is %.1f±%.1f/sec. "
                                                    + "The 15 minute average rate is %.1f±%.1f/sec. "
                                                    + "At the 95% confidence level both are above 100.0/sec. "
                                                    + "If this message is repeated often in the logs then PLEASE "
                                                    + "seriously consider setting system property 'hudson.remoting"
                                                    + ".RemoteInvocationHandler.Unexporter.retainOrigin' to "
                                                    + "'false' to trade debug diagnostics for reduced memory "
                                                    + "pressure."
                                            : "The all time average rate is %.1f±%.1f/sec. "
                                                    + "The 15 minute average rate is %.1f±%.1f/sec. "
                                                    + "At the 95%% confidence level both are above 100.0/sec. ",
                                    tAvg,
                                    tStd,
                                    m15Avg,
                                    m15Std));
                    return;
                }
                logger.log(
                        Level.WARNING,
                        String.format(
                                retainOrigin
                                        ? "The 15 minute average rate is %.1f±%.1f/sec. "
                                                + "At the 95% confidence level this is above 100.0/sec. "
                                                + "If this message is repeated often in the logs then very "
                                                + "seriously consider setting system property 'hudson.remoting"
                                                + ".RemoteInvocationHandler.Unexporter.retainOrigin' to "
                                                + "'false' to trade debug diagnostics for reduced memory "
                                                + "pressure."
                                        : "The 15 minute average rate is %.1f±%.1f/sec. "
                                                + "At the 95%% confidence level this is above 100.0/sec. ",
                                m15Avg,
                                m15Std));
                return;
            }
            if (m5Std > 1 && 100 < m5Avg - 2 * m5Std) {
                logger.log(
                        Level.WARNING,
                        String.format(
                                retainOrigin
                                        ? "The 5 minute average rate is %.1f±%.1f/sec. "
                                                + "At the 95% confidence level this is above 100.0/sec. "
                                                + "If this message is repeated often in the logs then "
                                                + "seriously consider setting system property 'hudson.remoting"
                                                + ".RemoteInvocationHandler.Unexporter.retainOrigin' to "
                                                + "'false' to trade debug diagnostics for reduced memory "
                                                + "pressure."
                                        : "The 5 minute average rate is %.1f±%.1f/sec. "
                                                + "At the 95%% confidence level this is above 100.0/sec. ",
                                m5Avg,
                                m5Std));
                return;
            }
            if (m1Std > 1 && 100 < m1Avg - 2 * m1Std) {
                logger.log(
                        Level.INFO,
                        String.format(
                                retainOrigin
                                        ? "The 1 minute average rate is %.1f±%.1f/sec. "
                                                + "At the 95% confidence level this is above 100.0/sec. "
                                                + "If this message is repeated often in the logs then "
                                                + "consider setting system property 'hudson.remoting"
                                                + ".RemoteInvocationHandler.Unexporter.retainOrigin' to "
                                                + "'false' to trade debug diagnostics for reduced memory "
                                                + "pressure."
                                        : "The 1 minute average rate is %.1f±%.1f/sec. "
                                                + "At the 95%% confidence level this is above 100.0/sec. ",
                                m1Avg,
                                m1Std));
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
            for (PhantomReferenceImpl phantom : referenceList) {
                if (phantom != null) {
                    phantom.clear();
                }
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
                referenceLists.putIfAbsent(ref, Collections.synchronizedList(new ArrayList<>()));
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
     * For user-space commands and operations, there is a {@link UserRPCRequest} implementation.
     *
     * @see UserRPCRequest
     */
    static class RPCRequest extends Request<Serializable, Throwable>
            implements DelegatingCallable<Serializable, Throwable>, InternalCallable<Serializable, Throwable> {
        // this callable only executes public methods exported by this side, so these methods are assumed to be safe
        /**
         * Target object id to invoke.
         */
        protected final int oid;

        /**
         * Type name of declaring class.
         * Null if deserialized historically.
         */
        @CheckForNull
        private final String declaringClassName;

        protected final String methodName;
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
        @CheckForNull
        @SuppressFBWarnings(
                value = "SE_TRANSIENT_FIELD_NOT_RESTORED",
                justification = "We're fine with the default null on the recipient side")
        private final transient ClassLoader classLoader;

        private RPCRequest(
                int oid, Method m, Object[] arguments, @CheckForNull ClassLoader cl, boolean recordCreatedAt) {
            super(recordCreatedAt);
            this.oid = oid;
            this.arguments = arguments;
            declaringClassName = m.getDeclaringClass().getName();
            this.methodName = m.getName();
            this.classLoader = cl;

            this.types = new String[arguments.length];
            Class<?>[] params = m.getParameterTypes();
            for (int i = 0; i < arguments.length; i++) {
                types[i] = params[i].getName();
            }
        }

        @Override
        public Serializable call() throws Throwable {
            return perform(getChannelOrFail());
        }

        @Override
        public ClassLoader getClassLoader() {
            if (classLoader != null) {
                return classLoader;
            } else {
                return getClass().getClassLoader();
            }
        }

        @Override
        protected Serializable perform(@NonNull Channel channel) throws Throwable {
            Object o = channel.getExportedObject(oid);
            Class<?>[] clazz = channel.getExportedTypes(oid);
            try {
                Method m = choose(clazz);
                if (m == null) {
                    throw new IllegalStateException("Unable to call " + methodName + ". No matching method found in "
                            + Arrays.toString(clazz) + " for " + o);
                }
                m.setAccessible(true); // in case the class is not public
                Object r;
                try {
                    r = m.invoke(o, arguments);
                } catch (IllegalArgumentException x) {
                    throw new RemotingSystemException(
                            "failed to invoke " + m + " on " + o + Arrays.toString(arguments), x);
                }
                if (r == null || r instanceof Serializable) {
                    return (Serializable) r;
                } else {
                    throw new RemotingSystemException(new ClassCastException(r.getClass() + " is returned from " + m
                            + " on " + o.getClass() + " but it's not serializable"));
                }
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }

        /**
         * Chooses the method to invoke.
         */
        private Method choose(Class<?>[] interfaces) {
            for (Class<?> clazz : interfaces) {
                OUTER:
                for (Method m : clazz.getMethods()) {
                    if (!m.getName().equals(methodName)) {
                        continue;
                    }
                    Class<?>[] paramTypes = m.getParameterTypes();
                    if (paramTypes.length != arguments.length) {
                        continue;
                    }
                    for (int i = 0; i < types.length; i++) {
                        if (!types[i].equals(paramTypes[i].getName())) {
                            continue OUTER;
                        }
                    }
                    return m;
                }
            }
            return null;
        }

        Object[] getArguments() { // for debugging
            return arguments;
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder(getClass().getSimpleName())
                    .append(':')
                    .append(declaringClassName)
                    .append('.')
                    .append(methodName)
                    .append('[');
            for (int i = 0; i < types.length; i++) {
                if (i > 0) {
                    b.append(',');
                }
                b.append(types[i]);
            }
            b.append("](").append(oid).append(')');
            return b.toString();
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * User-space version of {@link RPCRequest}.
     *
     * This is an equivalent of {@link UserRequest} for RPC calls.
     * Such kind of requests will not be send over closing or malfunctional channel.
     *
     * If used as {@link Callable} in conjunction with {@link UserRequest},
     * this can be used to send a method call to user-level objects, and
     * classes for the parameters and the return value are sent remotely if needed.
     */
    private static class UserRPCRequest extends RPCRequest {

        private static final long serialVersionUID = -9185841650347902580L;

        UserRPCRequest(int oid, Method m, Object[] arguments, ClassLoader cl, boolean recordCreatedAt) {
            super(oid, m, arguments, cl, recordCreatedAt);
        }

        // Same implementation as UserRequest
        @Override
        public void checkIfCanBeExecutedOnChannel(@NonNull Channel channel) throws IOException {
            // Default check for all requests
            super.checkIfCanBeExecutedOnChannel(channel);

            // We also do not want to run UserRequests when the channel is being closed
            if (channel.isClosingOrClosed()) {
                throw new ChannelClosedException(
                        channel,
                        "The request cannot be executed on channel " + channel + ". "
                                + "The channel is closing down or has closed down",
                        channel.getCloseRequestCause());
            }
        }
    }

    private static final Object[] EMPTY_ARRAY = new Object[0];
}
