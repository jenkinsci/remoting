package hudson.remoting;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.util.ExecutorServiceUtils;

import javax.annotation.Nonnull;

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
    private final ConcurrentMap<Checksum,Future<URL>> inprogress = new ConcurrentHashMap<>();

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
    @Nonnull
    public Future<URL> resolve(@Nonnull final Channel channel, final long sum1, final long sum2) throws IOException, InterruptedException {
        URL jar = lookInCache(channel,sum1, sum2);
        if (jar!=null) {
            // already in the cache
            return new AsyncFutureImpl<>(jar);
        }

        while (true) {// might have to try a few times before we get successfully resolve

            final Checksum key = new Checksum(sum1,sum2);        
            Future<URL> cur = inprogress.get(key);
            if (cur!=null) {
                // this computation is already in progress. piggy back on that one
                return cur;
            } else {
                // we are going to resolve this ourselves and publish the result in 'promise' for others
                try {
                    final AsyncFutureImpl<URL> promise = new AsyncFutureImpl<>();
                    ExecutorServiceUtils.submitAsync(downloader, new  DownloadRunnable(channel, sum1, sum2, key, promise));
                    // Now we are sure that the task has been accepted to the queue, hence we cache the promise
                    // if nobody else caches it before.
                    inprogress.putIfAbsent(key, promise);
                } catch (ExecutorServiceUtils.ExecutionRejectedException ex) {
                    final String message = "Downloader executor service has rejected the download command for checksum " + key;
                    LOGGER.log(Level.SEVERE, message, ex);
                    // Retry the submission after 100 ms if the error is not fatal
                    if (ex.isFatal()) {
                        // downloader won't accept anything else, do not even try
                        throw new IOException(message, ex);
                    } else {
                        //TODO: should we just fail? unrealistic case for the current AtmostOneThreadExecutor implementation anyway
                        Thread.sleep(100);
                    }
                }
            }
        }
    }
    
    private class DownloadRunnable implements Runnable {
    
        final Channel channel;
        final long sum1;
        final long sum2;
        final Checksum key;
        final AsyncFutureImpl<URL> promise;

        public DownloadRunnable(Channel channel, long sum1, long sum2, Checksum key, AsyncFutureImpl<URL> promise) {
            this.channel = channel;
            this.sum1 = sum1;
            this.sum2 = sum2;
            this.key = key;
            this.promise = promise;
        }
        
        @Override
        public void run() {
            try {
                // Deduplication: There is a risk that multiple downloadables get scheduled, hence we check if
                // the promise is actually in the queue
                Future<URL> inprogressDownload = inprogress.get(key);
                if (promise != inprogressDownload) {
                    // Duplicated entry due to the race condition, do nothing
                    return;
                }
                
                URL url = retrieve(channel, sum1, sum2);
                inprogress.remove(key);
                promise.set(url);
            } catch (ChannelClosedException | RequestAbortedException e) {
                // the connection was killed while we were still resolving the file
                bailout(e);
            } catch (InterruptedException e) {
                // we are bailing out, but we need to allow another thread to retry later.
                bailout(e);

                LOGGER.log(Level.WARNING, String.format("Interrupted while resolving a jar %016x%016x", sum1, sum2), e);
            } catch (Throwable e) {
                // in other general failures, we aren't retrying
                // TODO: or should we?
                promise.set(e);

                LOGGER.log(Level.WARNING, String.format("Failed to resolve a jar %016x%016x", sum1, sum2), e);
            }
        }

        /**
         * Report a failure of the retrieval and allows another thread to retry.
         */
        private void bailout(Exception e) {
            inprogress.remove(key);     // this lets another thread to retry later
            promise.set(e);             // then tell those who are waiting that we aborted
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
