package hudson.remoting;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.StreamCorruptedException;

/**
 * The default {@link CommandTransport} that has been used historically.
 *
 * <p>
 * This implementation builds a {@link SynchronousCommandTransport} on top of a plain bi-directional byte stream.
 * {@link Channel.Mode} support allows this to be built on 8-bit unsafe transport, such as telnet.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.13
 */
/*package*/ final class ClassicCommandTransport extends SynchronousCommandTransport {
    private final ObjectInputStream ois;
    private final ObjectOutputStream oos;
    private final Capability remoteCapability;
    /**
     * Transport level {@link InputStream} that we use only for diagnostics in case we detect stream
     * corruption. Can be null.
     */
    private final @Nullable FlightRecorderInputStream rawIn;
    /**
     * See {@link CommandTransport#getUnderlyingStream()}
     */
    private final OutputStream rawOut;

    /*package*/ ClassicCommandTransport(
            ObjectInputStream ois,
            ObjectOutputStream oos,
            @CheckForNull FlightRecorderInputStream rawIn,
            OutputStream rawOut,
            Capability remoteCapability) {
        this.ois = ois;
        this.oos = oos;
        this.rawIn = rawIn;
        this.rawOut = rawOut;
        this.remoteCapability = remoteCapability;
    }

    @Override
    public Capability getRemoteCapability() throws IOException {
        return remoteCapability;
    }

    @Override
    public final void write(Command cmd, boolean last) throws IOException {
        cmd.writeTo(channel, oos);
        // TODO notifyWrite using CountingOutputStream
        oos.flush(); // make sure the command reaches the other end.

        // unless this is the last command, have OOS and remote OIS forget all the objects we sent
        // in this command. Otherwise it'll keep objects in memory unnecessarily.
        // However, this may fail if the command was the close, because that's supposed to be the last command
        // ever sent. It is possible for our ReaderThread to receive the reflecting close call from the other side
        // and close the output before the sending code gets to here.
        // See the comment from jglick on JENKINS-3077 about what happens if we do oos.reset().
        if (!last) {
            oos.reset();
        }
    }

    @Override
    public void closeWrite() throws IOException {
        oos.close();
    }

    @Override
    public final Command read() throws IOException, ClassNotFoundException {
        try {
            Command cmd = Command.readFromObjectStream(channel, ois);
            // TODO notifyRead using CountingInputStream
            if (rawIn != null) {
                rawIn.clear();
            }
            return cmd;
        } catch (RuntimeException | StreamCorruptedException e) { // see JENKINS-19046
            throw diagnoseStreamCorruption(e);
        }
    }

    /**
     * To diagnose stream corruption, we'll try to read ahead the data.
     * This operation can block, so we'll use another thread to do this.
     */
    private StreamCorruptedException diagnoseStreamCorruption(Exception e) {
        if (rawIn == null) { // no source of diagnostics information. can't diagnose.
            if (e instanceof StreamCorruptedException) {
                return (StreamCorruptedException) e;
            } else {
                return (StreamCorruptedException) new StreamCorruptedException().initCause(e);
            }
        }

        return rawIn.analyzeCrash(e, (channel != null ? channel : this).toString());
    }

    @Override
    public void closeRead() throws IOException {
        ois.close();
    }

    @Override
    OutputStream getUnderlyingStream() {
        return rawOut;
    }
}
