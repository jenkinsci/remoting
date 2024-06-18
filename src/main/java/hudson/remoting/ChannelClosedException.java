package hudson.remoting;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.jenkinsci.remoting.ChannelStateException;

/**
 * Indicates that the channel is already closed or being closed.
 *
 * @author Kohsuke Kawaguchi
 */
public class ChannelClosedException extends ChannelStateException {
    /**
     * @deprecated
     *      Use {@link #ChannelClosedException(Throwable)} or {@link #ChannelClosedException(String, Throwable)}.
     *      This constructor will not include cause of the termination.
     */
    @Deprecated
    public ChannelClosedException() {
        this(null, "channel is already closed", null);
    }

    /**
     * @deprecated Use {@link #ChannelClosedException(Channel, Throwable)}
     */
    @Deprecated
    public ChannelClosedException(Throwable cause) {
        this((Channel) null, cause);
    }

    /**
     * Constructor.
     * @param channel Reference to the channel. {@code null} if the channel is unknown.
     * @param cause Cause
     * @since 3.15
     */
    public ChannelClosedException(@CheckForNull Channel channel, @CheckForNull Throwable cause) {
        super(channel, "channel is already closed", cause);
    }

    /**
     * Constructor.
     *
     * @param message Message
     * @param cause Cause of the channel close/termination.
     *              May be {@code null} if it cannot be determined when the exception is constructed.
     * @since 3.11
     * @deprecated Use {@link #ChannelClosedException(Channel, String, Throwable)}
     */
    @Deprecated
    public ChannelClosedException(@NonNull String message, @CheckForNull Throwable cause) {
        this(null, message, cause);
    }

    /**
     * Constructor.
     *
     * @param channel Reference to the channel. {@code null} if the channel is unknown.
     * @param message Message
     * @param cause Cause of the channel close/termination.
     *              May be {@code null} if it cannot be determined when the exception is constructed.
     * @since 3.15
     */
    public ChannelClosedException(
            @CheckForNull Channel channel, @NonNull String message, @CheckForNull Throwable cause) {
        super(channel, message, cause);
    }
}
