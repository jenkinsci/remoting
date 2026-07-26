package hudson.remoting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

/**
 * Implements {@link JarLoader} to be called from the other side.
 *
 * @author Kohsuke Kawaguchi
 */
class JarLoaderImpl implements JarLoader, SerializableOnlyOverRemoting {

    private static final Logger LOGGER = Logger.getLogger(JarLoaderImpl.class.getName());

    private static final ConcurrentMap<Checksum, URI> KNOWN_JARS = new ConcurrentHashMap<>();

    private static final ConcurrentMap<URI, CompletableFuture<Checksum>> CHECKSUMS = new ConcurrentHashMap<>();

    @SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "TODO needs triage")
    private final Set<Checksum> presentOnRemote = Collections.synchronizedSet(new HashSet<>());

    @Override
    @SuppressFBWarnings(
            value = {"URLCONNECTION_SSRF_FD", "PATH_TRAVERSAL_IN", "IMPROPER_UNICODE"},
            justification =
                    "This is only used for managing the jar cache as files, not URLs. file scheme matching is correct")
    public void writeJarTo(long sum1, long sum2, OutputStream sink) throws IOException, InterruptedException {
        Checksum k = new Checksum(sum1, sum2);
        URI uri = KNOWN_JARS.get(k);
        if (uri == null) {
            throw new IOException("Unadvertised jar file " + k);
        }

        Channel channel = Channel.current();
        if (channel != null) {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                try {
                    LOGGER.log(Level.FINE, () -> "sending " + uri + " to " + channel.getName());
                    channel.notifyJar(new File(uri));
                } catch (IllegalArgumentException x) {
                    LOGGER.log(Level.WARNING, x, () -> "cannot properly report " + uri);
                }
            } else {
                LOGGER.log(Level.FINE, "serving non-file URI {0}", uri);
            }
        } else {
            LOGGER.log(Level.WARNING, "no active channel");
        }
        Util.copy(uri.toURL().openStream(), sink);
        presentOnRemote.add(k);
    }

    public Checksum calcChecksum(File jar) throws IOException {
        return calcChecksum(jar.toURI());
    }

    @Override
    public boolean isPresentOnRemote(Checksum sum) {
        return presentOnRemote.contains(sum);
    }

    @Override
    public void notifyJarPresence(long sum1, long sum2) {
        presentOnRemote.add(new Checksum(sum1, sum2));
    }

    @Override
    public void notifyJarPresence(long[] sums) {
        synchronized (presentOnRemote) {
            for (int i = 0; i < sums.length; i += 2) {
                presentOnRemote.add(new Checksum(sums[i * 2], sums[i * 2 + 1]));
            }
        }
    }

    /**
     * Obtains the checksum for the jar at the specified URI.
     */
    public Checksum calcChecksum(URI jar) throws IOException {
        // Use Future-based memoizer so that internal map locks (which can be shared for different keys)
        // are never held during I/O / digest computation.

        CompletableFuture<Checksum> slot = new CompletableFuture<>();
        CompletableFuture<Checksum> existing = CHECKSUMS.putIfAbsent(jar, slot);
        if (existing != null) {
            // another thread already has a slot — wait for their result without locking
            slot = existing;
        } else {
            // we won the race, compute the checksum then update the future
            try {
                Checksum c = Checksum.forURL(jar.toURL());
                KNOWN_JARS.put(c, jar);
                slot.complete(c);
            } catch (IOException | Error | RuntimeException e) {
                CHECKSUMS.remove(jar, slot);
                slot.completeExceptionally(e);
                throw e;
            }
        }
        try {
            return slot.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for checksum of " + jar, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IOException("unexpected condition calculating checksum of " + jar, cause);
        }
    }

    /**
     * When sent to the remote node, send a proxy.
     */
    private Object writeReplace() throws NotSerializableException {
        return getChannelForSerialization().export(JarLoader.class, this);
    }

    public static final String DIGEST_ALGORITHM =
            System.getProperty(JarLoaderImpl.class.getName() + ".algorithm", "SHA-256");

    private static final long serialVersionUID = 1L;
}
