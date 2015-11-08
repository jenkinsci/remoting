package hudson.remoting;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * {@link SynchronousCommandTransport} that works with {@code byte[]} instead of command object.
 * 
 * This base class hides away some of the {@link Command} serialization details. One less thing
 * for transport implementers to worry about.
 * 
 * @author Kohsuke Kawaguchi
 * @since 2.13
 */
public abstract class AbstractSynchronousByteArrayCommandTransport extends SynchronousCommandTransport {
    /**
     * Read a byte[] from the underlying transport for the given channel.
     */
    public abstract byte[] readBlock(Channel channel) throws IOException, ClassNotFoundException;

    /**
     * Writes a byte[] to the transport.
     * 
     * The block boundary is significant. A transport needs to ensure that that the same byte[] is
     * read by the peer through {@link #readBlock(Channel)} (unlike TCP, where a single write can
     * be split into multiple read()s on the other side.)
     */
    public abstract void writeBlock(Channel channel, byte[] payload) throws IOException;

    @Override
    public Command read() throws IOException, ClassNotFoundException {
        return Command.readFrom(channel,new ObjectInputStreamEx(
                new ByteArrayInputStream(readBlock(channel)),
                channel.baseClassLoader,channel.classFilter));
    }

    @Override
    public void write(Command cmd, boolean last) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        cmd.writeTo(channel,oos);
        oos.close();
        writeBlock(channel,baos.toByteArray());
    }
}
