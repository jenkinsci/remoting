package hudson.remoting;

import hudson.remoting.Channel.Mode;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

/**
 * @author Kohsuke Kawaguchi
 */
/*package*/ final class ByteStreamCommandTransport extends CommandTransport {
    private final ObjectInputStream ois;
    private final ObjectOutputStream oos;
    private final Capability remoteCapability;
    private final OutputStream underlyingStream;

    private ByteStreamCommandTransport(ObjectInputStream ois, ObjectOutputStream oos, OutputStream underlyingStream, Capability remoteCapability) {
        this.ois = ois;
        this.oos = oos;
        this.underlyingStream = underlyingStream;
        this.remoteCapability = remoteCapability;
    }

    @Override
    public Capability getRemoteCapability(Channel channel) throws IOException {
        return remoteCapability;
    }

    public final void write(Channel channel, Command cmd, boolean last) throws IOException {
        Channel old = Channel.setCurrent(channel);
        try {
            oos.writeObject(cmd);
            oos.flush();        // make sure the command reaches the other end.
        } finally {
            Channel.setCurrent(old);
        }
        if(!last)
            oos.reset();
    }

    public void closeWrite(Channel channel) throws IOException {
        oos.close();
    }

    public final Command read(Channel channel) throws IOException, ClassNotFoundException {
        Channel old = Channel.setCurrent(channel);
        try {
            return (Command)ois.readObject();
        } finally {
            Channel.setCurrent(old);
        }
    }

    public void closeRead(Channel channel) throws IOException {
        ois.close();
    }

    @Override
    OutputStream getUnderlyingStream() {
        return underlyingStream;
    }

    public static CommandTransport create(Mode mode, InputStream is, OutputStream os, OutputStream header, ClassLoader base, Capability capability) throws IOException {
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

                                return new ByteStreamCommandTransport(
                                        new ObjectInputStreamEx(mode.wrap(is),base),
                                        oos, os, cap);
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
