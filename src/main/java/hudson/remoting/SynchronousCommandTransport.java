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
    abstract Command read() throws IOException, ClassNotFoundException, InterruptedException;

    @Override
    public void setup(Channel channel, CommandReceiver receiver) {
        this.channel = channel;
        new ReaderThread(receiver).start();
    }

    private final class ReaderThread extends Thread {
        private int commandsReceived = 0;
        private int commandsExecuted = 0;
        private final CommandReceiver receiver;

        public ReaderThread(CommandReceiver receiver) {
            super("Channel reader thread: "+channel.getName());
            this.receiver = receiver;
        }

        @Override
        public void run() {
            final String name =channel.getName();
            try {
                while(!channel.isInClosed()) {
                    Command cmd = null;
                    try {
                        cmd = read();
                    } catch (SocketTimeoutException ex) {
                        if (RDR_FAIL_ON_SOCKET_TIMEOUT) {
                            LOGGER.log(Level.SEVERE, "Socket timeout in the Synchronous channel reader."
                                    + " The channel will be interrupted, because " + RDR_SOCKET_TIMEOUT_PROPERTY_NAME 
                                    + " is set", ex);
                            throw ex;
                        }
                        // Timeout happened during the read operation.
                        // It is not always fatal, because it may be caused by a long-running command
                        // If channel is not closed, it's OK to continue reading the channel
                        LOGGER.log(Level.WARNING, "Socket timeout in the Synchronous channel reader", ex);
                        continue;
                    } catch (EOFException e) {
                        IOException ioe = new IOException("Unexpected termination of the channel");
                        ioe.initCause(e);
                        throw ioe;
                    } catch (ClassNotFoundException e) {
                        LOGGER.log(Level.SEVERE, "Unable to read a command (channel " + name + ")",e);
                        continue;
                    } finally {
                        commandsReceived++;
                    }

                    receiver.handle(cmd);
                    commandsExecuted++;
                }
                closeRead();
            } catch (InterruptedException e) {
                LOGGER.log(Level.SEVERE, "I/O error in channel "+name,e);
                channel.terminate((InterruptedIOException) new InterruptedIOException().initCause(e));
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "I/O error in channel "+name,e);
                channel.terminate(e);
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Unexpected error in channel "+name,e);
                channel.terminate((IOException) new IOException("Unexpected reader termination").initCause(e));
                throw e;
            } catch (Error e) {
                LOGGER.log(Level.SEVERE, "Unexpected error in channel "+name,e);
                channel.terminate((IOException) new IOException("Unexpected reader termination").initCause(e));
                throw e;
            } finally {
                channel.pipeWriter.shutdown();
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(SynchronousCommandTransport.class.getName());
}
