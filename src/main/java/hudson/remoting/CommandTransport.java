package hudson.remoting;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Lower level abstraction under {@link Channel} for sending and receiving commands
 * 
 * <p>
 * {@link Channel} is internally implemented on top of a transport that satisfies
 * the following characteristics:
 * 
 * <dl>
 * <dt>Point-to-point</dt>
 * <dd>
 *     Like TCP, by the time {@link CommandTransport} is used by {@link Channel}, it needs to be
 *     connected to "the other side". The write operation doesn't take the receiver address,
 *     and the read operation doesn't return the sender address. A {@link CommandTransport} talks
 *     to one and the only peer throughout its life.
 * </dd>
 * <dt>Packet-oriented</dt>
 * <dd>
 *     Like UDP, each read/write operation acts on a single {@link Command} object, sent across
 *     in its serialized form.
 * </dd>
 * <dt>Reliable and in-order</dt>
 * <dd>
 *     {@link Command}s that are written need to arrive in the exact same order, without any loss,
 *     or else both sides must raise an error, like TCP.
 * </dd>
 * </dl>
 * 
 * <p>
 * {@linkplain ByteStreamCommandTransport the default traditional implementation} implements
 * this on top of a TCP-like bi-directional byte stream, but {@link Channel} can work with
 * any {@link CommandTransport} that provides a similar hook.
 *
 * <h2>Serialization of {@link Command}</h2>
 * <p>
 * {@link Command} objects need to be serialized and deseralized in a specific environment
 * so that {@link Command}s can access {@link Channel} that's using it. Since such logic
 * can change, we don't allow this class to be directly subtyped. Please extend from
 * {@link AbstractCommandTransportImpl} instead.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class CommandTransport {
    /**
     * Package private so as not to allow direct subtyping (just yet.)
     */
    /*package*/ CommandTransport() {
    }

    /**
     * 
     */
    public abstract Capability getRemoteCapability(Channel channel) throws IOException;

    /**
     * Called by {@link Channel} to send one command to the other side.
     * 
     * @param channel
     *      The calling {@link Channel} object, provided as a contextual information.
     * @param cmd
     *      The command object that needs to be sent. Never null.
     * @param last
     *      Indicates that this is the last command.
     */
    abstract void write(Channel channel, Command cmd, boolean last) throws IOException;

    /**
     * Called to close the write side of the transport, allowing the underlying transport
     * to be shut down.
     *
     * @param channel
     *      The calling {@link Channel} object, provided as a contextual information.
     */
    public abstract void closeWrite(Channel channel) throws IOException;

    /**
     * Called by {@link Channel} to read the next command to arrive from the stream.
     *
     * @param channel
     *      The calling {@link Channel} object, provided as a contextual information.
     * @param cmd
     *      The command object that needs to be sent. Never null.
     * @param last
     *      Indicates that this is the last command.
     */
    abstract Command read(Channel channel) throws IOException, ClassNotFoundException, InterruptedException;
    public abstract void closeRead(Channel channel) throws IOException;

    /**
     * Historical artifact left for backward compatibility, necessary only for retaining
     * {@link Channel#getUnderlyingOutput()}.
     * 
     * <p>
     * Historically, {@link CommandTransport} abstraction is introduced much later than
     * {@link Channel}. The implementation of the historical {@link Channel} was mostly
     * OK that lets us cleanly introduce the {@link CommandTransport} abstraction, but
     * there was one method in Channel that assumed that there's the underlying {@link OutputStream}
     * (that now belongs to the implementation details of {@link CommandTransport}),
     * hence this method.
     * 
     * <p>
     * This method is package private, to prevent new {@link CommandTransport} from
     * providing this feature.
     */
    OutputStream getUnderlyingStream() {
        return null;
    }
}
