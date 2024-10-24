package hudson.remoting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import org.jenkinsci.remoting.util.AnonymousClassWarnings;

/**
 * Represents additional features implemented on {@link Channel}.
 *
 * <p>
 * Each {@link Channel} exposes its capability to {@link Channel#getProperty(Object)}.
 *
 * <p>
 * This mechanism allows two different versions of {@code remoting.jar} to talk to each other.
 *
 * @author Kohsuke Kawaguchi
 * @see Channel#remoteCapability
 */
public final class Capability implements Serializable {

    /**
     * Key usable as a WebSocket HTTP header to negotiate capabilities.
     */
    public static final String KEY = "X-Remoting-Capability";

    /**
     * Bit mask of optional capabilities.
     */
    private final long mask;

    Capability(long mask) {
        this.mask = mask;
    }

    public Capability() {
        this(MASK_MULTI_CLASSLOADER
                | MASK_PIPE_THROTTLING
                | MASK_MIMIC_EXCEPTION
                | MASK_PREFETCH
                | GREEDY_REMOTE_INPUTSTREAM
                | MASK_PROXY_WRITER_2_35
                | MASK_CHUNKED_ENCODING
                | PROXY_EXCEPTION_FALLBACK);
    }

    /**
     * Does this implementation supports multi-classloader serialization in
     * {@link UserRequest}?
     *
     * @see MultiClassLoaderSerializer
     */
    public boolean supportsMultiClassLoaderRPC() {
        return (mask & MASK_MULTI_CLASSLOADER) != 0;
    }

    /**
     * Does the implementation supports window size control over pipes?
     *
     * @see ProxyOutputStream
     */
    public boolean supportsPipeThrottling() {
        return (mask & MASK_PIPE_THROTTLING) != 0;
    }

    /** @deprecated no longer used */
    @Deprecated
    public boolean hasMimicException() {
        return (mask & MASK_MIMIC_EXCEPTION) != 0;
    }

    /**
     * Supports chunking to designate a command boundary.
     *
     * <p>
     * In this mode, the wire format of the data changes to:
     * <ul>
     * <li>Include the framing (length+payload) so that a command boundary
     *     can be discovered without understanding the Java serialization wire format.
     * <li>Each command is serialized by its {@link ObjectOutputStream}
     * </ul>
     *
     * This is necessary for the NIO transport to work.
     *
     * @see ChunkHeader
     */
    public boolean supportsChunking() {
        return (mask & MASK_CHUNKED_ENCODING) != 0;
    }

    /**
     * Does the implementation allow classes to be prefetched and JARs to be cached?
     * @since 2.24
     */
    public boolean supportsPrefetch() {
        return (mask & MASK_PREFETCH) != 0;
    }

    /**
     * Does {@link RemoteInputStream} supports greedy flag.
     *
     * @since 2.35
     */
    public boolean supportsGreedyRemoteInputStream() {
        return (mask & GREEDY_REMOTE_INPUTSTREAM) != 0;
    }

    /**
     * Does {@link ProxyWriter} supports proper throttling?
     *
     * This flag is also used to check other improvements made in ProxyWriter at the same time.
     *
     * @since 2.35
     */
    public boolean supportsProxyWriter2_35() {
        return (mask & MASK_PROXY_WRITER_2_35) != 0;
    }

    /**
     * Supports {@link ProxyException} as a fallback when failing to deserialize {@link UserRequest} exceptions.
     * @since 3.19
     * @see <a href="https://issues.jenkins-ci.org/browse/JENKINS-50237">JENKINS-50237</a>
     */
    public boolean supportsProxyExceptionFallback() {
        return (mask & PROXY_EXCEPTION_FALLBACK) != 0;
    }

    // TODO: ideally preamble handling needs to be reworked in order to avoid FB suppression
    /**
     * Writes {@link #PREAMBLE} then uses {@link #write}.
     */
    void writePreamble(OutputStream os) throws IOException {
        os.write(PREAMBLE);
        write(os);
    }

    /**
     * Writes this capability to a stream.
     */
    private void write(OutputStream os) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(Channel.Mode.TEXT.wrap(os)) {
            @Override
            public void close() throws IOException {
                flush();
                // TODO: Cannot invoke the private clear() method, but GC well do it for us. Not worse than the original
                // solution
                // Here the code does not close the proxied stream OS on completion
            }

            @Override
            protected void annotateClass(Class<?> c) throws IOException {
                AnonymousClassWarnings.check(c);
                super.annotateClass(c);
            }
        }) {
            oos.writeObject(this);
            oos.flush();
        }
    }

    /**
     * The opposite operation of {@link #write}.
     */
    @SuppressFBWarnings(
            value = "OBJECT_DESERIALIZATION",
            justification =
                    "Capability is used for negotiating channel between authorized agent and server. Whitelisting and proper deserialization hygiene are used.")
    public static Capability read(InputStream is) throws IOException {
        try (ObjectInputStream ois = new ObjectInputStream(Channel.Mode.TEXT.wrap(is)) {
            // during deserialization, only accept Capability to protect ourselves
            // from malicious payload. Allow java.lang.String so that
            // future versions of Capability can send more complex data structure.
            // If we decide to do so in the future, the payload will contain those instances
            // even though our version of Capability class will discard them after deserialization.
            @Override
            protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                String n = desc.getName();
                if (n.equals("java.lang.String")
                        || n.equals("[Ljava.lang.String;")
                        || n.equals(Capability.class.getName())) {
                    return super.resolveClass(desc);
                }
                throw new SecurityException("Rejected: " + n);
            }

            @Override
            public void close() throws IOException {
                // Do not close the stream since we continue reading from the input stream "is"
            }
        }) {
            return (Capability) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw (Error) new NoClassDefFoundError(e.getMessage()).initCause(e);
        }
    }

    /**
     * Uses {@link #write} to serialize this object to a Base64-encoded ASCII stream.
     */
    public String toASCII() throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            write(baos);
            return baos.toString(StandardCharsets.US_ASCII);
        }
    }

    /**
     * The inverse of {@link #toASCII}.
     */
    public static Capability fromASCII(String ascii) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(ascii.getBytes(StandardCharsets.US_ASCII))) {
            return Capability.read(bais);
        }
    }

    private static final long serialVersionUID = 1L;

    /**
     * This was used briefly to indicate the use of {@link MultiClassLoaderSerializer}, but
     * that was disabled (see HUDSON-4293) in Sep 2009. AFAIK no released version of Hudson
     * exposed it, but since then the wire format of {@link MultiClassLoaderSerializer} has evolved
     * in an incompatible way.
     * <p>
     * So just to be on the safe side, I assigned a different bit to indicate this feature {@link #MASK_MULTI_CLASSLOADER},
     * so that even if there are remoting.jar out there that advertizes this bit, we won't be using
     * the new {@link MultiClassLoaderSerializer} code.
     * <p>
     * If we ever use up all 64bits of long, we can probably come back and reuse this bit, as by then
     * hopefully any such remoting.jar deployment is long gone.
     */
    private static final long MASK_UNUSED1 = 1L << 0;

    /**
     * Bit that indicates the use of {@link MultiClassLoaderSerializer}.
     */
    private static final long MASK_MULTI_CLASSLOADER = 1L << 1;

    /**
     * Bit that indicates the use of TCP-like window control for {@link ProxyOutputStream}.
     */
    private static final long MASK_PIPE_THROTTLING = 1L << 2;

    /**
     * Supports {@link MimicException}.
     */
    @Deprecated
    private static final long MASK_MIMIC_EXCEPTION = 1L << 3;

    /**
     * This flag indicates the support for advanced classloading features.
     *
     * <p>
     * This mainly involves two things:
     *
     * <ul>
     * <li>Prefetching, where a request to retrieve a class also reports where
     *     related classes can be found and loaded, which saves roundtrips.
     * <li>Caching, where we separate "which classloader should load a class" from
     *     "which jar file should load a class", enabling caching at the jar files level.
     * </ul>
     *
     * @see ResourceImageRef
     */
    private static final long MASK_PREFETCH = 1L << 4;

    /**
     * Support for {@link RemoteInputStream#greedy}.
     */
    private static final long GREEDY_REMOTE_INPUTSTREAM = 1L << 5;

    /**
     * Support for pipe window and other modern stuff in {@link ProxyWriter}.
     * @since 2.35
     */
    private static final long MASK_PROXY_WRITER_2_35 = 1L << 6;

    /**
     * Supports chunked encoding.
     *
     * @since 2.38
     */
    private static final long MASK_CHUNKED_ENCODING = 1L << 7;

    private static final long PROXY_EXCEPTION_FALLBACK = 1L << 8;

    static final byte[] PREAMBLE = "<===[JENKINS REMOTING CAPACITY]===>".getBytes(StandardCharsets.UTF_8);

    public static final Capability NONE = new Capability(0);

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Capability{");
        boolean first = true;
        if ((mask & MASK_MULTI_CLASSLOADER) != 0) {
            first = false;
            sb.append("Multi-ClassLoader RPC");
        }
        if ((mask & MASK_PIPE_THROTTLING) != 0) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append("Pipe throttling");
        }
        if ((mask & MASK_MIMIC_EXCEPTION) != 0) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append("Mimic Exception");
        }
        if ((mask & MASK_PREFETCH) != 0) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append("Prefetch");
        }
        if ((mask & GREEDY_REMOTE_INPUTSTREAM) != 0) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append("Greedy RemoteInputStream");
        }
        if ((mask & MASK_PROXY_WRITER_2_35) != 0) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append("Proxy writer 2.35");
        }
        if ((mask & MASK_CHUNKED_ENCODING) != 0) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append("Chunked encoding");
        }
        if ((mask & PROXY_EXCEPTION_FALLBACK) != 0) {
            if (first) {
                first = false;
            } else {
                sb.append(", ");
            }
            sb.append("ProxyException fallback");
        }
        sb.append('}');
        return sb.toString();
    }
}
