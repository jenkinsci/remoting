package hudson.remoting;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class JarCacheSupport extends JarCache {

    private final ConcurrentMap<Checksum,Future<URL>> inprogress = new ConcurrentHashMap<Checksum, Future<URL>>();


    @Override
    public URL resolve(Channel channel, long sum1, long sum2) throws IOException, InterruptedException {
        URL jar = lookInCache(channel,sum1, sum2);
        if (jar!=null) {
            // already in the cache
            return jar;
        }

        while (true) {// might have to try a few times before we get successfully resolve

            Checksum key = new Checksum(sum1,sum2);
            AsyncFutureImpl<URL> promise = new AsyncFutureImpl<URL>();
            Future<URL> cur = inprogress.putIfAbsent(key, promise);
            if (cur!=null) {
                // this computation is already in progress. piggy back on that one
                try {
                    return cur.get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof InterruptedException) {
                        // the other guy who was trying to retrieve aborted,
                        // so we need to retry
                        continue;
                    }
                    throw (IOException)new IOException(String.format("Failed to resolve a jar %016x%016x",sum1,sum2)).initCause(e);
                }
            } else {
                // we are going to resolve this ourselves and publish the result in 'promise' for others
                JarLoader jl = channel.getProperty(JarLoader.THEIRS);
                if (jl==null) {// even if two threads run this simultaneously, it is harmless
                    jl = (JarLoader) channel.waitForRemoteProperty(JarLoader.OURS);
                    channel.setProperty(JarLoader.THEIRS,jl);
                }
                try {
                    URL url = retrieve(channel,sum1,sum2,jl);
                    promise.set(url);
                    return url;
                } catch (InterruptedException e) {
                    // we are bailing out, but we need to allow another thread to retry later.
                    inprogress.put(key,null);   // this lets another thread to retry later
                    promise.set(e);             // then tell those who are waiting that we aborted
                } catch (Throwable e) {
                    // in other general failures, we aren't retrying
                    // TODO: or should we?
                    promise.set(e);

                    if (e instanceof RuntimeException)
                        throw (RuntimeException)e;
                    if (e instanceof Error)
                        throw (Error)e;
                    if (e instanceof IOException)
                        throw (IOException)e;

                    throw (IOException)new IOException(String.format("Failed to resolve a jar %016x%016x",sum1,sum2)).initCause(e);
                }
            }
        }
    }

    protected abstract URL lookInCache(Channel channel, long sum1, long sum2) throws IOException;
    protected abstract URL retrieve(Channel channel, long sum1, long sum2, JarLoader jl) throws IOException, InterruptedException;
}
