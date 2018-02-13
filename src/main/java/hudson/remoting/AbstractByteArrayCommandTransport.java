package hudson.remoting;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

/**
 * {@link CommandTransport} that works with {@code byte[]} instead of command object.
 *
 * This base class hides away some of the {@link Command} serialization details. One less thing
 * for transport implementers to worry about.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.13
 */
public abstract class AbstractByteArrayCommandTransport extends CommandTransport {
    protected Channel channel;
    
    /**
     * Writes a byte[] to the transport.
     * 
     * The block boundary is significant. A transport needs to ensure that that the same byte[] is
     * read by the peer (unlike TCP, where a single write can
     * be split into multiple read()s on the other side.)
     */
    public abstract void writeBlock(Channel channel, byte[] payload) throws IOException;

    /**
     * Starts the transport.
     * 
     * See {@link #setup(Channel, CommandReceiver)} for more details.
     * 
     * In this subtype, we pass in {@link ByteArrayReceiver} that uses byte[] instead of {@link Command}
     */
    public abstract void setup(@Nonnull ByteArrayReceiver receiver);
    
    public static interface ByteArrayReceiver {
        /**
         * Notifies the {@link Channel} that the transport has received a new block.
         * 
         * As discussed in {@link AbstractByteArrayCommandTransport#writeBlock(Channel, byte[])},
         * the block boundary is significant.
         */
        void handle(byte[] payload);

        /**
         * See {@link CommandReceiver#handle(Command)} for details.
         */
        void terminate(IOException e);
    }
    
    @Override
    public final void setup(final Channel channel, final CommandReceiver receiver) {
        this.channel = channel;
        setup(new ByteArrayReceiver() {
            public void handle(byte[] payload) {
                try {
                    receiver.handle(Command.readFrom(channel, new ObjectInputStreamEx(
                            new ByteArrayInputStream(payload),channel.baseClassLoader,channel.classFilter)));
                } catch (IOException | ClassNotFoundException e) {
                    LOGGER.log(Level.WARNING, "Failed to construct Command in channel " + channel.getName(), e);
                }
            }

            public void terminate(IOException e) {
                receiver.terminate(e);
            }
        });
    }

    @Override
    public final void write(Command cmd, boolean last) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        cmd.writeTo(channel,oos);
        oos.close();
        writeBlock(channel,baos.toByteArray());
    }

    private static final Logger LOGGER = Logger.getLogger(AbstractByteArrayCommandTransport.class.getName());
}
