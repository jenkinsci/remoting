package hudson.remoting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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

    private final ConcurrentMap<Checksum, URL> knownJars = new ConcurrentHashMap<>();

    private final ConcurrentMap<URL, Checksum> checksums = new ConcurrentHashMap<>();

    private final Set<Checksum> presentOnRemote = Collections.synchronizedSet(new HashSet<>());

    @Override
    @SuppressFBWarnings(
            value = {"URLCONNECTION_SSRF_FD", "PATH_TRAVERSAL_IN"},
            justification = "This is only used for managing the jar cache as files, not URLs.")
    public void writeJarTo(long sum1, long sum2, OutputStream sink) throws IOException, InterruptedException {
        Checksum k = new Checksum(sum1, sum2);
        URL url = knownJars.get(k);
        if (url == null) {
            throw new IOException("Unadvertised jar file " + k);
        }

        Channel channel = Channel.current();
        if (channel != null) {
            if (url.getProtocol().equals("file")) {
                try {
                    LOGGER.log(Level.FINE, () -> "sending " + url + " to " + channel.getName());
                    channel.notifyJar(new File(url.toURI()));
                } catch (URISyntaxException | IllegalArgumentException x) {
                    LOGGER.log(Level.WARNING, x, () -> "cannot properly report " + url);
                }
            } else {
                LOGGER.log(Level.FINE, "serving non-file URL {0}", url);
            }
        } else {
            LOGGER.log(Level.WARNING, "no active channel");
        }
        Util.copy(url.openStream(), sink);
        presentOnRemote.add(k);
    }

    public Checksum calcChecksum(File jar) throws IOException {
        return calcChecksum(jar.toURI().toURL());
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
     * Obtains the checksum for the jar at the specified URL.
     */
    public Checksum calcChecksum(URL jar) throws IOException {
        Checksum v = checksums.get(jar); // cache hit
        if (v != null) {
            return v;
        }

        v = Checksum.forURL(jar);

        knownJars.put(v, jar);
        checksums.put(jar, v);
        return v;
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
