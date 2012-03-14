package hudson.remoting;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class AbstractCommandTransportImpl extends SynchronousCommandTransport {
    public abstract byte[] readBlock(Channel channel) throws IOException, ClassNotFoundException;

    public abstract void writeBlock(Channel channel, byte[] payload) throws IOException;

    @Override
    public Command read() throws IOException, ClassNotFoundException {
        byte[] payload = readBlock(channel);

        Channel old = Channel.setCurrent(channel);
        try {
            return (Command)new ObjectInputStreamEx(new ByteArrayInputStream(payload),channel.baseClassLoader).readObject();
        } finally {
            Channel.setCurrent(old);
        }
    }

    @Override
    public void write(Command cmd, boolean last) throws IOException {
        Channel old = Channel.setCurrent(channel);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(cmd);
            oos.close();
            writeBlock(channel,baos.toByteArray());
        } finally {
            Channel.setCurrent(old);
        }
    }
}
