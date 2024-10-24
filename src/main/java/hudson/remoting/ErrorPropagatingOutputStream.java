package hudson.remoting;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@link OutputStream} that's connected to an {@link InputStream} somewhere,
 * which provides ability to have {@link InputStream} report an error.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.35
 */
public interface ErrorPropagatingOutputStream {
    /**
     * Closes the stream and causes the reading {@link InputStream} to report an error.
     *
     * <p>
     * This method is somewhat like {@link OutputStream#close()},
     * in that it signals the end of a stream. In addition to what the close method does,
     * this method will cause the {@link InputStream#read()}
     * method (or any other overloaded versions) to throw an
     * {@link IOException} with the given throwable as the cause.
     *
     * <p>
     * {@link InputStream} will report an error only after all the data that has written
     * before is read. IOW, the error will not magically jump over the data that was written.
     *
     * <p>
     * This is useful to propagate error over a pipe. If used over
     * a channel with the remoting library that doesn't yet support this,
     * or if the {@link OutputStream} isn't connecting to an {@link InputStream},
     * this method behaves exactly like {@link OutputStream#close()}.
     *
     * <p>
     * If {@link OutputStream} is already closed or error state is
     * set, this method will be no-op.
     *
     * @param e
     *      if null, this method behaves exactly like {@link OutputStream#close()}
     */
    void error(@CheckForNull Throwable e) throws IOException;
}
