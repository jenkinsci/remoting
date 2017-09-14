package hudson.remoting;

import java.io.IOException;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Indicates that the channel is already closed or being closed.
 *
 * @author Kohsuke Kawaguchi
 */
public class ChannelClosedException extends IOException {
    /**
     * @deprecated
     *      Use {@link #ChannelClosedException(Throwable)} or {@link #ChannelClosedException(java.lang.String, java.lang.Throwable)}.
     *      This constructor will not include cause of the termination.
     */
    @Deprecated
    public ChannelClosedException() {
        super("channel is already closed");
    }

    public ChannelClosedException(Throwable cause) {
        super("channel is already closed", cause);
    }
    
    /**
     * Constructor.
     * 
     * @param message Message
     * @param cause Cause of the channel close/termination. 
     *              May be {@code null} if it cannot be determined when the exception is constructed.
     * @since 3.11
     */
    public ChannelClosedException(@Nonnull String message, @CheckForNull Throwable cause) {
        super(message, cause);
    }
}
