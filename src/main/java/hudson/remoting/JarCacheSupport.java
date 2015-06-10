package hudson.remoting;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default partial implementation of {@link JarCache}.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.24
 */
public abstract class JarCacheSupport extends JarCache {
    /**
     * Remember in-progress jar file resolution to avoid retrieving the same jar file twice.
     */
    private final ConcurrentMap<Checksum,Future<URL>> inprogress = new ConcurrentHashMap<Checksum, Future<URL>>();

    /**
     * Look up the local cache and return URL if found.
     * Otherwise null (which will trigger a remote retrieval.)
     */
    protected abstract URL lookInCache(Channel channel, long sum1, long sum2) throws IOException, InterruptedException;

    /**
     * Retrieve the jar file from the given {@link JarLoader}, store it, then return the URL to that jar.
     *
     * @return must not be null
     */
    protected abstract URL retrieve(Channel channel, long sum1, long sum2) throws IOException, InterruptedException;

    /**
     * Throttle the jar downloading activity so that it won't eat up all the channel bandwidth.
     */
    private final ExecutorService downloader = new AtmostOneThreadExecutor(
            new NamingThreadFactory(new DaemonThreadFactory(), JarCacheSupport.class.getSimpleName())
    );

    @Override
    public Future<URL> resolve(final Channel channel, final long sum1, final long sum2) throws IOException, InterruptedException {
        URL jar = lookInCache(channel,sum1, sum2);
        if (jar!=null) {
            // already in the cache
            return new AsyncFutureImpl<URL>(jar);
        }

        while (true) {// might have to try a few times before we get successfully resolve

            final Checksum key = new Checksum(sum1,sum2);
            final AsyncFutureImpl<URL> promise = new AsyncFutureImpl<URL>();
            Future<URL> cur = inprogress.putIfAbsent(key, promise);
            if (cur!=null) {
                // this computation is already in progress. piggy back on that one
                return cur;
            } else {
                // we are going to resolve this ourselves and publish the result in 'promise' for others
                downloader.submit(new Runnable() {
                    public void run() {
                        try {
                            URL url = retrieve(channel,sum1,sum2);
                            inprogress.remove(key);
                            promise.set(url);
                        } catch (ChannelClosedException e) {
                            // the connection was killed while we were still resolving the file
                            bailout(e);
                        } catch (RequestAbortedException e) {
                            // the connection was killed while we were still resolving the file
                            bailout(e);
                        } catch (InterruptedException e) {
                            // we are bailing out, but we need to allow another thread to retry later.
                            bailout(e);

                            LOGGER.log(Level.WARNING, String.format("Interrupted while resolving a jar %016x%016x",sum1,sum2), e);
                        } catch (Throwable e) {
                            // in other general failures, we aren't retrying
                            // TODO: or should we?
                            promise.set(e);

                            LOGGER.log(Level.WARNING, String.format("Failed to resolve a jar %016x%016x",sum1,sum2), e);
                        }
                    }

                    /**
                     * Report a failure of the retrieval and allows another thread to retry.
                     */
                    private void bailout(Exception e) {
                        inprogress.remove(key);     // this lets another thread to retry later
                        promise.set(e);             // then tell those who are waiting that we aborted
                    }
                });
            }
        }
    }

    protected JarLoader getJarLoader(Channel channel) throws InterruptedException {
        JarLoader jl = channel.getProperty(JarLoader.THEIRS);
        if (jl==null) {// even if two threads run this simultaneously, it is harmless
            jl = (JarLoader) channel.waitForRemoteProperty(JarLoader.OURS);
            channel.setProperty(JarLoader.THEIRS,jl);
        }
        return jl;
    }

    private static final Logger LOGGER = Logger.getLogger(JarCacheSupport.class.getName());
}
