package hudson.remoting;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link CommandTransport} that implements the read operation in a synchronous fashion.
 *
 * <p>
 * This class uses a thread to pump commands and pass them to {@link CommandReceiver}.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class SynchronousCommandTransport extends CommandTransport {
    protected Channel channel;

    private static final String RDR_SOCKET_TIMEOUT_PROPERTY_NAME =
            SynchronousCommandTransport.class.getName() + ".failOnSocketTimeoutInReader";

    /**
     * Enables the original aggressive behavior, when the channel reader gets
     * interrupted on any {@link SocketTimeoutException}.
     * @since 2.60
     */
    private static boolean RDR_FAIL_ON_SOCKET_TIMEOUT = Boolean.getBoolean(RDR_SOCKET_TIMEOUT_PROPERTY_NAME);

    /**
     * Called by {@link Channel} to read the next command to arrive from the stream.
     */
    public abstract Command read() throws IOException, ClassNotFoundException, InterruptedException;

    @Override
    public void setup(Channel channel, CommandReceiver receiver) {
        this.channel = channel;
        new ReaderThread(receiver).start();
    }

    private final class ReaderThread extends Thread {
        private final CommandReceiver receiver;

        public ReaderThread(CommandReceiver receiver) {
            super("Channel reader thread: " + channel.getName());
            this.receiver = receiver;
            setUncaughtExceptionHandler((t, e) -> {
                LOGGER.log(
                        Level.SEVERE, e, () -> "Uncaught exception in SynchronousCommandTransport.ReaderThread " + t);
                channel.terminate(new IOException("Unexpected reader termination", e));
            });
        }

        @Override
        public void run() {
            final String name = channel.getName();
            try {
                while (!channel.isInClosed()) {
                    Command cmd;
                    try {
                        cmd = read();
                    } catch (SocketTimeoutException ex) {
                        if (RDR_FAIL_ON_SOCKET_TIMEOUT) {
                            LOGGER.log(
                                    Level.SEVERE,
                                    ex,
                                    () -> "Socket timeout in the Synchronous channel reader."
                                            + " The channel will be interrupted, because "
                                            + RDR_SOCKET_TIMEOUT_PROPERTY_NAME
                                            + " is set");
                            throw ex;
                        }
                        // Timeout happened during the read operation.
                        // It is not always fatal, because it may be caused by a long-running command
                        // If channel is not closed, it's OK to continue reading the channel
                        LOGGER.log(Level.WARNING, "Socket timeout in the Synchronous channel reader", ex);
                        continue;
                    } catch (EOFException e) {
                        throw new IOException("Unexpected termination of the channel", e);
                    } catch (ClassNotFoundException e) {
                        LOGGER.log(Level.SEVERE, e, () -> "Unable to read a command (channel " + name + ")");
                        continue;
                    }

                    receiver.handle(cmd);
                }
                closeRead();
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, e, () -> "I/O error in channel " + name);
                Thread.currentThread().interrupt();
                channel.terminate((InterruptedIOException) new InterruptedIOException().initCause(e));
            } catch (IOException e) {
                LOGGER.log(Level.INFO, e, () -> "I/O error in channel " + name);
                channel.terminate(e);
            } catch (RuntimeException | Error e) {
                LOGGER.log(Level.SEVERE, e, () -> "Unexpected error in channel " + name);
                channel.terminate(new IOException("Unexpected reader termination", e));
                throw e;
            } finally {
                channel.pipeWriter.shutdown();
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(SynchronousCommandTransport.class.getName());
}
