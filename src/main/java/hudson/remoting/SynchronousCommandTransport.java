package hudson.remoting;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
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
abstract class SynchronousCommandTransport extends CommandTransport {
    protected Channel channel;

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
