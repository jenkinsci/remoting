package hudson.remoting;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
 * {@linkplain ClassicCommandTransport the default traditional implementation} implements
 * this on top of a TCP-like bi-directional byte stream, but {@link Channel} can work with
 * any {@link CommandTransport} that provides a similar hook.
 *
 * <h2>Serialization of {@link Command}</h2>
 * <p>
 * {@link Command} objects need to be serialized and deseralized in a specific environment
 * so that {@link Command}s can access {@link Channel} that's using it. Because of this,
 * a transport needs to use {@link Command#writeTo(Channel, ObjectOutputStream)} and
 * {@link Command#readFromObjectStream(Channel, ObjectInputStream)} or
 * {@link Command#readFrom(Channel, byte[])}.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.13
 */
public abstract class CommandTransport {

    protected CommandTransport() {}

    /**
     * SPI implemented by {@link Channel} so that the transport can pass the received command
     * to {@link Channel} for processing.
     */
    protected interface CommandReceiver {
        /**
         * Notifies the channel that a new {@link Command} was received from the other side.
         *
         * <p>
         * {@link Channel} performs all the error recovery of the error resulting from the command invocation.
         * {@link Channel} implements this method in a non-reentrant fashion; a transport can invoke this method
         * from different threads, but as the class javadoc states, the transport must
         * guarantee in-order delivery of the commands, and that means you cannot call this method
         * concurrently.
         *
         * @param cmd
         *      The command received. This object must be read from the payload
         *      using {@link Command#readFrom(Channel, byte[])}.
         */
        void handle(Command cmd);

        /**
         * Indicates that the transport has encountered a problem.
         * This tells the channel that it shouldn't expect future invocation of {@link #handle(Command)},
         * and it'll abort the communication.
         */
        void terminate(IOException e);
    }

    /**
     * Abstraction of the connection hand-shaking.
     *
     * <p>
     * Before two channels can talk to each other,
     */
    public abstract Capability getRemoteCapability() throws IOException;

    /**
     * Starts the transport.
     *
     * This method is called once and only once at the end of the initialization of {@link Channel},
     * after the {@link #getRemoteCapability()} is invoked.
     *
     * The first purpose of this method is to provide a reference back to {@link Channel}, and
     * the second purpose of this method is to allow {@link CommandTransport} to message pumping,
     * where it starts receiving commands from the other side and pass them onto {@link CommandReceiver}.
     *
     * This abstraction enables asynchronous processing &mdash; for example you can have a single thread
     * serving a large number of {@link Channel}s via NIO.
     *
     * For subtypes that prefer synchronous operation, extend from {@link SynchronousCommandTransport}.
     *
     * <p>
     * <strong>Closing the read pump:</strong> {@link Channel} implements
     * {@code Channel.CloseCommand} its own "end of command stream" marker, and
     * therefore under the orderly shutdown scenario, it doesn't rely on the transport to provide EOF-like
     * marker. Instead, {@link Channel} will call your {@link #closeRead()} (from the same thread
     * that invoked {@link CommandReceiver#handle(Command)}) to indicate that it is done with the reading.
     *
     * <p>
     * If the transport encounters any error from the lower layer (say, the underlying TCP/IP socket
     * encountered a REST), then call {@link CommandReceiver#terminate(IOException)} to initiate the abnormal
     * channel termination. This in turn calls {@link #closeRead()} to shutdown the reader side.
     */
    public abstract void setup(Channel channel, CommandReceiver receiver);

    /**
     * Called by {@link Channel} to send one command to the other side.
     *
     * {@link Channel} serializes the invocation of this method for ordering. That is,
     * at any given point in time only one thread executes this method.
     *
     * <p>
     * Asynchronous transport must serialize the given command object before
     * returning from this method, as its content can be modified by the calling
     * thread as soon as this method returns. Also, if an asynchronous transport
     * chooses to return from this method without committing data to the network,
     * then it is also responsible for a flow control (by blocking this method
     * if too many commands are queueing up waiting for the network to unclog.)
     *
     * @param cmd
     *      The command object that needs to be sent. Never null. This must be
     *      serialized via {@link Command#writeTo(Channel, ObjectOutputStream)}
     * @param last
     *      Informational flag that indicates that this is the last
     *      call of the {@link #write(Command, boolean)}.
     */
    public abstract void write(Command cmd, boolean last) throws IOException;

    /**
     * Called to close the write side of the transport, allowing the underlying transport
     * to be shut down.
     *
     * <p>
     * If the {@link Channel} aborts the communication, this method may end up invoked
     * asynchronously, concurrently, and multiple times. The implementation must protect
     * against that.
     */
    public abstract void closeWrite() throws IOException;

    /**
     * Called to indicate that the no further input is expected and any resources
     * associated with reading commands should be freed.
     *
     * {@link Channel#isInClosed()} can be also used to test if the command reading
     * should terminate.
     */
    public abstract void closeRead() throws IOException;

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
    @CheckForNull
    OutputStream getUnderlyingStream() {
        return null;
    }
}
