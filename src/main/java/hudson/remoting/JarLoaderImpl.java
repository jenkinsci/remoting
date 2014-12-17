package hudson.remoting;

import hudson.remoting.forward.Forwarder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URL;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Implements {@link JarLoader} to be called from the other side.
 *
 * TODO: move {@link #knownJars} and {@link #checksums} to another class to share it across
 * {@link JarLoaderImpl}s.
 *
 * @author Kohsuke Kawaguchi
 */
@edu.umd.cs.findbugs.annotations.SuppressWarnings("SE_BAD_FIELD")
class JarLoaderImpl implements JarLoader, Serializable {
    private final ConcurrentMap<Checksum,URL> knownJars = new ConcurrentHashMap<Checksum,URL>();

    @edu.umd.cs.findbugs.annotations.SuppressWarnings("DMI_COLLECTION_OF_URLS") // TODO: fix this
    private final ConcurrentMap<URL,Checksum> checksums = new ConcurrentHashMap<URL,Checksum>();

    private final Set<Checksum> presentOnRemote = Collections.synchronizedSet(new HashSet<Checksum>());

    public void writeJarTo(long sum1, long sum2, OutputStream sink) throws IOException, InterruptedException {
        Checksum k = new Checksum(sum1, sum2);
        URL url = knownJars.get(k);
        if (url==null)
            throw new IOException("Unadvertised jar file "+k);

        Util.copy(url.openStream(), sink);
        presentOnRemote.add(k);
    }

    public Checksum calcChecksum(File jar) throws IOException {
        return calcChecksum(jar.toURI().toURL());
    }

    public boolean isPresentOnRemote(Checksum sum) {
        return presentOnRemote.contains(sum);
    }

    public void notifyJarPresence(long sum1, long sum2) {
        presentOnRemote.add(new Checksum(sum1,sum2));
    }

    public void notifyJarPresence(long[] sums) {
        synchronized (presentOnRemote) {
            for (int i=0; i<sums.length; i+=2)
                presentOnRemote.add(new Checksum(sums[i*2],sums[i*2+1]));
        }
    }

    /**
     * Obtains the checksum for the jar at the specified URL.
     */
    public Checksum calcChecksum(URL jar) throws IOException {
        Checksum v = checksums.get(jar);    // cache hit
        if (v!=null)    return v;

        try {
            MessageDigest md = MessageDigest.getInstance(DIGEST_ALGORITHM);
            Util.copy(jar.openStream(),new DigestOutputStream(new NullOutputStream(),md));
            v = new Checksum(md.digest(),md.getDigestLength()/8);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }

        knownJars.put(v,jar);
        checksums.put(jar,v);
        return v;
    }

    class NullOutputStream extends OutputStream {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    }

    /**
     * When sent to the remote node, send a proxy.
     */
    private Object writeReplace() {
        return Channel.current().export(JarLoader.class, this);
    }

    public static final String DIGEST_ALGORITHM = System.getProperty(JarLoaderImpl.class.getName()+".algorithm","SHA-256");

    private static final long serialVersionUID = 1L;
}
