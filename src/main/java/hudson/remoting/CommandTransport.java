package hudson.remoting;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Lower level abstraction for sending and receiving commands.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class CommandTransport {
    public abstract Capability getRemoteCapability() throws IOException;

    public abstract void write(Channel channel, Command cmd, boolean last) throws IOException;
    public abstract void closeWrite() throws IOException;

    public abstract Command read(Channel channel) throws IOException, ClassNotFoundException;
    public abstract void closeRead() throws IOException;
    
    protected OutputStream getUnderlyingStream() {
        return null;
    }
}
