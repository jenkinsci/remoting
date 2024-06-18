package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
    private final ConcurrentMap<Checksum, CompletableFuture<URL>> inprogress = new ConcurrentHashMap<>();

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
    private final ExecutorService downloader = newCachingSingleThreadExecutor(
            new NamingThreadFactory(new DaemonThreadFactory(), JarCacheSupport.class.getSimpleName()));

    private static ExecutorService newCachingSingleThreadExecutor(ThreadFactory threadFactory) {
        ThreadPoolExecutor threadPoolExecutor =
                new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), threadFactory);
        threadPoolExecutor.allowCoreThreadTimeOut(true);
        return threadPoolExecutor;
    }

    @Override
    @NonNull
    public CompletableFuture<URL> resolve(@NonNull final Channel channel, final long sum1, final long sum2)
            throws IOException, InterruptedException {
        URL jar = lookInCache(channel, sum1, sum2);
        if (jar != null) {
            // already in the cache
            return CompletableFuture.completedFuture(jar);
        }

        final Checksum key = new Checksum(sum1, sum2);
        return inprogress.computeIfAbsent(key, unused -> submitDownload(channel, sum1, sum2, key));
    }

    @NonNull
    @SuppressFBWarnings(value = "RV_RETURN_VALUE_IGNORED_BAD_PRACTICE", justification = "API compatibility")
    private CompletableFuture<URL> submitDownload(Channel channel, long sum1, long sum2, Checksum key) {
        final CompletableFuture<URL> promise = new CompletableFuture<>();
        downloader.submit(new DownloadRunnable(channel, sum1, sum2, key, promise));
        return promise;
    }

    private class DownloadRunnable implements Runnable {

        final Channel channel;
        final long sum1;
        final long sum2;
        final Checksum key;
        final CompletableFuture<URL> promise;

        public DownloadRunnable(Channel channel, long sum1, long sum2, Checksum key, CompletableFuture<URL> promise) {
            this.channel = channel;
            this.sum1 = sum1;
            this.sum2 = sum2;
            this.key = key;
            this.promise = promise;
        }

        @Override
        public void run() {
            try {
                URL url = retrieve(channel, sum1, sum2);
                if (inprogress.remove(key, promise)) {
                    promise.complete(url);
                } else {
                    promise.completeExceptionally(
                            new IllegalStateException("Download is (unexpectedly) no longer in progress"));
                }
            } catch (ChannelClosedException | RequestAbortedException e) {
                // the connection was killed while we were still resolving the file
                bailout(e);
            } catch (InterruptedException e) {
                // we are bailing out, but we need to allow another thread to retry later.
                bailout(e);

                LOGGER.log(Level.WARNING, String.format("Interrupted while resolving a jar %016x%016x", sum1, sum2), e);
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                if (channel.isClosingOrClosed()) {
                    bailout(e);
                } else {
                    // in other general failures, we aren't retrying
                    // TODO: or should we?
                    promise.completeExceptionally(e);
                    LOGGER.log(Level.WARNING, String.format("Failed to resolve a jar %016x%016x", sum1, sum2), e);
                }
            }
        }

        /**
         * Report a failure of the retrieval and allows another thread to retry.
         */
        private void bailout(Throwable e) {
            inprogress.remove(key, promise); // this lets another thread to retry later
            promise.completeExceptionally(e); // then tell those who are waiting that we aborted
        }
    }

    protected JarLoader getJarLoader(Channel channel) throws InterruptedException {
        JarLoader jl = channel.getProperty(JarLoader.THEIRS);
        if (jl == null) { // even if two threads run this simultaneously, it is harmless
            jl = (JarLoader) channel.waitForRemoteProperty(JarLoader.OURS);
            channel.setProperty(JarLoader.THEIRS, jl);
        }
        return jl;
    }

    private static final Logger LOGGER = Logger.getLogger(JarCacheSupport.class.getName());
}
