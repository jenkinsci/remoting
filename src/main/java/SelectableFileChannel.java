import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;

/**
 *
 * @deprecated
 *      This idea didn't work because {@link SelectionKey#channel()} revealed the underlying channel.
 *
 * @author Kohsuke Kawaguchi
 */
public class SelectableFileChannel extends SelectableChannel implements ByteChannel, ScatteringByteChannel, GatheringByteChannel {
    private final SocketChannel base;

    SelectableFileChannel(SocketChannel base) {
        this.base = base;
    }

    @Override
    public int validOps() {
        return base.validOps();
    }

    public boolean isConnected() {
        return base.isConnected();
    }

    public boolean isConnectionPending() {
        return base.isConnectionPending();
    }

    public boolean connect(SocketAddress remote) throws IOException {
        return base.connect(remote);
    }

    public boolean finishConnect() throws IOException {
        return base.finishConnect();
    }

    public int read(ByteBuffer dst) throws IOException {
        return base.read(dst);
    }

    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        return base.read(dsts, offset, length);
    }

    public long read(ByteBuffer[] dsts) throws IOException {
        return base.read(dsts);
    }

    public int write(ByteBuffer src) throws IOException {
        return base.write(src);
    }

    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        return base.write(srcs, offset, length);
    }

    public long write(ByteBuffer[] srcs) throws IOException {
        return base.write(srcs);
    }

    @Override
    public SelectorProvider provider() {
        return base.provider();
    }

    @Override
    public boolean isRegistered() {
        return base.isRegistered();
    }

    @Override
    public SelectionKey keyFor(Selector sel) {
        return base.keyFor(sel);
    }

    @Override
    public SelectionKey register(Selector sel, int ops, Object att) throws ClosedChannelException {
        return base.register(sel, ops, att);
    }

    @Override
    public boolean isBlocking() {
        return base.isBlocking();
    }

    @Override
    public Object blockingLock() {
        return base.blockingLock();
    }

    @Override
    public SelectableChannel configureBlocking(boolean block) throws IOException {
        return base.configureBlocking(block);
    }

    @Override
    protected void implCloseChannel() throws IOException {
       base.close();
    }
}
