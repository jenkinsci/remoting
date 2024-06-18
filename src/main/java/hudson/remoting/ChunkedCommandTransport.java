package hudson.remoting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * A variation of {@link ClassicCommandTransport} that uses the chunked encoding.
 *
 * @author Kohsuke Kawaguchi
 */
class ChunkedCommandTransport extends AbstractSynchronousByteArrayCommandTransport {
    private final Capability remoteCapability;

    private final ChunkedInputStream in;
    private final ChunkedOutputStream out;

    /**
     * See {@link CommandTransport#getUnderlyingStream()}
     */
    private final OutputStream rawOut;

    /*package*/ ChunkedCommandTransport(
            Capability remoteCapability, InputStream in, OutputStream out, OutputStream rawOut) {
        this.remoteCapability = remoteCapability;
        this.in = new ChunkedInputStream(in);
        this.out = new ChunkedOutputStream(8192, out);
        this.rawOut = rawOut;
    }

    @Override
    public Capability getRemoteCapability() throws IOException {
        return remoteCapability;
    }

    @Override
    public byte[] readBlock(Channel channel) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        in.readUntilBreak(baos);
        return baos.toByteArray();
    }

    @Override
    public void writeBlock(Channel channel, byte[] payload) throws IOException {
        out.write(payload);
        out.sendBreak();
    }

    @Override
    public void closeWrite() throws IOException {
        out.close();
    }

    @Override
    public void closeRead() throws IOException {
        in.close();
    }

    @Override
    OutputStream getUnderlyingStream() {
        return rawOut;
    }
}
