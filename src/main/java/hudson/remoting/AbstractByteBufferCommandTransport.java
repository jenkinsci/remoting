/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.util.AnonymousClassWarnings;
import org.jenkinsci.remoting.util.ByteBufferQueue;
import org.jenkinsci.remoting.util.ByteBufferQueueOutputStream;
import org.jenkinsci.remoting.util.FastByteBufferQueueInputStream;
import org.jenkinsci.remoting.util.IOUtils;

/**
 * A {@link CommandTransport} that uses {@link ByteBuffer} rather than {@link byte[]}.
 *
 * @since 3.0
 */
public abstract class AbstractByteBufferCommandTransport extends CommandTransport {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(AbstractByteBufferCommandTransport.class.getName());
    /**
     * The {@link #readState} when waiting for a frame header.
     */
    private static final int READ_STATE_NEED_HEADER = 0;
    /**
     * The {@link #readState} when waiting for the remaining byte of a frame header.
     */
    private static final int READ_STATE_MORE_HEADER = 1;
    /**
     * The {@link #readState} when waiting for {@link #readFrameRemaining} more bytes of frame body.
     */
    private static final int READ_STATE_FRAME_BODY = 2;
    /**
     * The {@link #readState} when a whole command has been read.
     */
    private static final int READ_STATE_COMMAND_READY = 3;
    /**
     * Our channel.
     */
    private Channel channel;

    @Deprecated
    private final ByteBuffer writeChunkHeader;
    /**
     * The transport frame size.
     */
    private int transportFrameSize = 8192;

    @Deprecated
    private ByteBuffer writeChunkBody;
    /**
     * The chunk header & body buffer.
     */
    private ByteBuffer writeChunkCombined;
    /**
     * The delegate, this is required as we cannot access some of the methods of {@link ChunkHeader} outside of the
     * remoting module.
     */
    private CommandReceiver receiver;
    /**
     * The queue of data that has been received and is waiting to be processed.
     */
    private final ByteBufferQueue receiveQueue = new ByteBufferQueue(transportFrameSize);
    /**
     * Internal tracking of the read state. We use primitive field rather than an enum to enhance data locality
     */
    private int readState;
    /**
     * When {@link #readState} is {@link #READ_STATE_FRAME_BODY} this is the number of remaining bytes in the current
     * frame.
     */
    private int readFrameRemaining;
    /**
     * When {@link #readState} is {@link #READ_STATE_MORE_HEADER} this is the first byte of the header,
     * when {@link #READ_STATE_FRAME_BODY} this is the {@link ChunkHeader#parse(int, int)} parsed header.
     */
    private int readFrameHeader;
    /**
     * The offset in {@link #readCommandSizes} holding the current command length.
     */
    private int readCommandIndex = 0;
    /**
     * The command lengths read in so far. Normally we just use index 0 but there is the case where some commands
     * may have arrived prior to {@link #setup(Channel, CommandReceiver)} in which case we just have to buffer all
     * the commands we get and parse them later.
     */
    private int[] readCommandSizes = new int[16];
    /**
     * The queue used to stage output.
     */
    private final ByteBufferQueue sendStaging = new ByteBufferQueue(transportFrameSize);

    /**
     * @deprecated Pass {@code true} to {@link #AbstractByteBufferCommandTransport(boolean)} and switch {@link #write(ByteBuffer, ByteBuffer)} to {@link #write(ByteBuffer)}.
     */
    @Deprecated
    protected AbstractByteBufferCommandTransport() {
        this(false);
    }

    protected AbstractByteBufferCommandTransport(boolean combineBuffers) {
        if (combineBuffers) {
            writeChunkHeader = null;
            writeChunkBody = null;
            writeChunkCombined = ByteBuffer.allocate(transportFrameSize + ChunkHeader.SIZE);
        } else { // deprecated
            writeChunkHeader = ByteBuffer.allocate(ChunkHeader.SIZE);
            writeChunkBody = ByteBuffer.allocate(transportFrameSize);
            writeChunkCombined = null;
        }
    }

    /**
     * @deprecated pass true to {@link #AbstractByteBufferCommandTransport(boolean)} and implement {@link #write(ByteBuffer)}
     */
    @Deprecated
    protected void write(ByteBuffer header, ByteBuffer data) throws IOException {
        throw new AbstractMethodError("implement write(ByteBuffer, ByteBuffer) if !combineBuffers");
    }

    /**
     * Write the packet.
     *
     * @param headerAndData the header and data to write.
     * @throws IOException if the data could not be written.
     */
    protected void write(ByteBuffer headerAndData) throws IOException {
        throw new AbstractMethodError("implement write(ByteBuffer) if combineBuffers");
    }

    /**
     * Handle receiving some data.
     *
     * @param data the data that has been received.
     * @throws IOException          if something goes wrong during the receive.
     * @throws InterruptedException if interrupted during the receive.
     */
    public final void receive(@NonNull ByteBuffer data) throws IOException, InterruptedException {
        while (receiver != null && readCommandIndex > 0) {
            processCommand();
        }
        while (data.hasRemaining() || readState == READ_STATE_COMMAND_READY) {
            switch (readState) {
                case READ_STATE_NEED_HEADER:
                    if (data.remaining() >= ChunkHeader.SIZE) {
                        // jump straight to state 2
                        readFrameHeader = ChunkHeader.read(data);
                        readFrameRemaining = ChunkHeader.length(readFrameHeader);
                        readState = READ_STATE_FRAME_BODY;
                    } else {
                        // store the first byte for resume
                        readFrameHeader = data.get();
                        readState = READ_STATE_MORE_HEADER;
                    }
                    break;
                case READ_STATE_MORE_HEADER:
                    // we have one byte already
                    readFrameHeader = ChunkHeader.parse(readFrameHeader, data.get());
                    readFrameRemaining = ChunkHeader.length(readFrameHeader);
                    readState = READ_STATE_FRAME_BODY;
                    break;
                case READ_STATE_FRAME_BODY:
                    if (data.remaining() < readFrameRemaining) {
                        readCommandSizes[readCommandIndex] += data.remaining();
                        readFrameRemaining -= data.remaining();
                        receiveQueue.put(data);
                    } else {
                        readCommandSizes[readCommandIndex] += readFrameRemaining;
                        int oldLimit = data.limit();
                        ((Buffer) data).limit(data.position() + readFrameRemaining);
                        receiveQueue.put(data);
                        ((Buffer) data).limit(oldLimit);
                        readFrameRemaining = 0;
                        if (ChunkHeader.isLast(readFrameHeader)) {
                            readState = READ_STATE_COMMAND_READY;
                        } else {
                            readState = READ_STATE_NEED_HEADER;
                        }
                    }
                    break;
                case READ_STATE_COMMAND_READY:
                    // we have read in a command
                    readCommandIndex++;
                    if (readCommandIndex > readCommandSizes.length) {
                        readCommandSizes = Arrays.copyOf(readCommandSizes, readCommandSizes.length * 2);
                    }
                    readCommandSizes[readCommandIndex] = 0;
                    readState = READ_STATE_NEED_HEADER;
                    if (receiver != null) {
                        processCommand();
                    }
                    break;
                default:
                    throw new IllegalStateException("Unknown readState = " + readState);
            }
        }
    }

    private void processCommand() throws IOException {
        try {
            FastByteBufferQueueInputStream is = new FastByteBufferQueueInputStream(receiveQueue, readCommandSizes[0]);
            try {
                Command cmd = Command.readFrom(channel, is, readCommandSizes[0]);
                receiver.handle(cmd);
            } catch (IOException | ClassNotFoundException e) {
                LOGGER.log(Level.WARNING, "Failed to construct Command in channel " + channel.getName(), e);
            } finally {
                int available = is.available();
                if (available > 0) {
                    if (is.skip(available) != available) {
                        // let's polish the decks of the Titanic
                        LOGGER.log(Level.FINE, "Failed to skip remainder of Command");
                    }
                }
                IOUtils.closeQuietly(is);
            }
        } finally {
            if (readCommandIndex == 1) {
                if (readCommandSizes.length > 16) {
                    // de-allocate the grown array
                    int temp = readCommandSizes[1];
                    readCommandSizes = new int[16];
                    readCommandSizes[0] = temp;
                } else {
                    readCommandSizes[0] = readCommandSizes[1];
                }
                readCommandIndex = 0;
            } else {
                // we have processed position 0
                // we now want position 1 to be the new position 0
                // position readCommandIndex has the readState data length, so need to keep that
                System.arraycopy(readCommandSizes, 1, readCommandSizes, 0, readCommandIndex);
                readCommandIndex--;
            }
        }
    }

    /**
     * Set the frame size.
     *
     * @param transportFrameSize the new frame size (must be in the range {@code 1}-{@link Short#MAX_VALUE}).
     */
    public void setFrameSize(int transportFrameSize) {
        if (transportFrameSize <= 0 || transportFrameSize > Short.MAX_VALUE) {
            throw new IllegalArgumentException();
        }
        this.transportFrameSize = transportFrameSize;
        // this is the only one that matters when it comes to sizing as we have to accept any frame size on receive
        if (writeChunkHeader == null) {
            writeChunkCombined = ByteBuffer.allocate(transportFrameSize + ChunkHeader.SIZE);
        } else {
            writeChunkBody = ByteBuffer.allocate(transportFrameSize);
        }
    }

    /**
     * Gets the channel.
     *
     * @return the channel.
     */
    @Nullable // only null before setup.
    protected Channel getChannel() {
        return channel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final synchronized void setup(final Channel channel, final CommandReceiver receiver) {
        this.channel = channel;
        this.receiver = receiver;
        try {
            // just in case any data was queued while we were waiting for the call to setup, let's trigger the
            // flush of the receive queue.
            while (receiver != null && readCommandIndex > 0) {
                processCommand();
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not flush receive buffer queue", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final void write(Command cmd, boolean last) throws IOException {
        ByteBufferQueueOutputStream bqos = new ByteBufferQueueOutputStream(sendStaging);
        try (ObjectOutputStream oos = AnonymousClassWarnings.checkingObjectOutputStream(bqos)) {
            cmd.writeTo(channel, oos);
        }
        long remaining = sendStaging.remaining();
        channel.notifyWrite(cmd, remaining);
        while (remaining > 0L) {
            int frame = remaining > transportFrameSize
                    ? transportFrameSize
                    : (int) remaining; // # of bytes we send in this chunk
            if (writeChunkHeader == null) {
                ((Buffer) writeChunkCombined).clear();
                ((Buffer) writeChunkCombined).limit(frame + ChunkHeader.SIZE);
                ChunkHeader.write(writeChunkCombined, frame, remaining > transportFrameSize);
                sendStaging.get(writeChunkCombined);
                ((Buffer) writeChunkCombined).flip();
                write(writeChunkCombined);
            } else {
                ((Buffer) writeChunkHeader).clear();
                ChunkHeader.write(writeChunkHeader, frame, remaining > transportFrameSize);
                ((Buffer) writeChunkHeader).flip();
                ((Buffer) writeChunkBody).clear();
                ((Buffer) writeChunkBody).limit(frame);
                sendStaging.get(writeChunkBody);
                ((Buffer) writeChunkBody).flip();
                write(writeChunkHeader, writeChunkBody);
            }
            remaining -= frame;
        }
    }

    /**
     * Indicates that the endpoint has encountered a problem.
     * This tells the transport that it shouldn't expect future invocation of {@link #receive(ByteBuffer)},
     * and it'll abort the communication.
     */
    public void terminate(IOException e) {
        receiver.terminate(e);
    }
}
