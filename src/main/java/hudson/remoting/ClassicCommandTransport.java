package hudson.remoting;

import hudson.remoting.Channel.Mode;

import javax.annotation.Nullable;
import java.io.EOFException;
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
 * {@link Mode} support allows this to be built on 8-bit unsafe transport, such as telnet.
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

    private ClassicCommandTransport(ObjectInputStream ois, ObjectOutputStream oos, FlightRecorderInputStream rawIn, OutputStream rawOut, Capability remoteCapability) {
        this.ois = ois;
        this.oos = oos;
        this.rawIn= rawIn;
        this.rawOut = rawOut;
        this.remoteCapability = remoteCapability;
    }

    @Override
    public Capability getRemoteCapability() throws IOException {
        return remoteCapability;
    }

    public final void write(Command cmd, boolean last) throws IOException {
        cmd.writeTo(channel,oos);
        oos.flush();        // make sure the command reaches the other end.

        // unless this is the last command, have OOS and remote OIS forget all the objects we sent
        // in this command. Otherwise it'll keep objects in memory unnecessarily.
        // However, this may fail if the command was the close, because that's supposed to be the last command
        // ever sent. It is possible for our ReaderThread to receive the reflecting close call from the other side
        // and close the output before the sending code gets to here.
        // See the comment from jglick on JENKINS-3077 about what happens if we do oos.reset().
        if(!last)
            oos.reset();
    }

    public void closeWrite() throws IOException {
        oos.close();
    }

    public final Command read() throws IOException, ClassNotFoundException {
        try {
            Command cmd = Command.readFrom(channel, ois);
            if (rawIn!=null)
                rawIn.clear();
            return cmd;
        } catch (RuntimeException e) {// see JENKINS-19046
            throw diagnoseStreamCorruption(e);
        } catch (StreamCorruptedException e) {
            throw diagnoseStreamCorruption(e);
        }
    }

    /**
     * To diagnose stream corruption, we'll try to read ahead the data.
     * This operation can block, so we'll use another thread to do this.
     */
    private StreamCorruptedException diagnoseStreamCorruption(Exception e) throws StreamCorruptedException {
        if (rawIn==null) {// no source of diagnostics information. can't diagnose.
            if (e instanceof StreamCorruptedException)
                return (StreamCorruptedException)e;
            else
                return (StreamCorruptedException)new StreamCorruptedException().initCause(e);
        }

        return rawIn.analyzeCrash(e,channel.toString());
    }

    public void closeRead() throws IOException {
        ois.close();
    }

    @Override
    OutputStream getUnderlyingStream() {
        return rawOut;
    }

    public static CommandTransport create(Mode mode, InputStream is, OutputStream os, OutputStream header, ClassLoader base, Capability capability) throws IOException {
        if (base==null)
            base = ClassicCommandTransport.class.getClassLoader();

        // write the magic preamble.
        // certain communication channel, such as forking JVM via ssh,
        // may produce some garbage at the beginning (for example a remote machine
        // might print some warning before the program starts outputting its own data.)
        //
        // so use magic preamble and discard all the data up to that to improve robustness.

        capability.writePreamble(os);

        ObjectOutputStream oos = null;
        if(mode!= Mode.NEGOTIATE) {
            os.write(mode.preamble);
            oos = new ObjectOutputStream(mode.wrap(os));
            oos.flush();    // make sure that stream preamble is sent to the other end. avoids dead-lock
        }

        {// read the input until we hit preamble
            Mode[] modes={Mode.BINARY,Mode.TEXT};
            byte[][] preambles = new byte[][]{Mode.BINARY.preamble, Mode.TEXT.preamble, Capability.PREAMBLE};
            int[] ptr=new int[3];
            Capability cap = new Capability(0); // remote capacity that we obtained. If we don't hear from remote, assume no capability

            while(true) {
                int ch = is.read();
                if(ch==-1)
                    throw new EOFException("unexpected stream termination");

                for(int i=0;i<preambles.length;i++) {
                    byte[] preamble = preambles[i];
                    if(preamble[ptr[i]]==ch) {
                        if(++ptr[i]==preamble.length) {
                            switch (i) {
                            case 0:
                            case 1:
                                // transmission mode negotiation
                                if(mode==Mode.NEGOTIATE) {
                                    // now we know what the other side wants, so send the consistent preamble
                                    mode = modes[i];
                                    os.write(mode.preamble);
                                    oos = new ObjectOutputStream(mode.wrap(os));
                                    oos.flush();
                                } else {
                                    if(modes[i]!=mode)
                                        throw new IOException("Protocol negotiation failure");
                                }

                                FlightRecorderInputStream fis = new FlightRecorderInputStream(is);
                                return new ClassicCommandTransport(
                                        new ObjectInputStreamEx(mode.wrap(fis),base),
                                        oos, fis, os, cap);
                            case 2:
                                cap = Capability.read(is);
                                break;
                            }
                            ptr[i]=0; // reset
                        }
                    } else {
                        // didn't match.
                        ptr[i]=0;
                    }
                }

                if(header!=null)
                    header.write(ch);
            }
        }
    }
}
