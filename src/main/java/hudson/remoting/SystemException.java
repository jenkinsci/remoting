package hudson.remoting;

/**
 * Remote proxy uses this exception to signal the exception thrown
 * by the remoting layer and not by the application code that was invoked remotely.
 *
 * <p>
 * The use of this exception triggers the caller to wrap a thrown exception to
 * add the stack trace that includes the call site information, making
 * the debugging easier
 *
 *
 * @author Kohsuke Kawaguchi
 */
class RemotingSystemException extends RuntimeException {
    RemotingSystemException(Throwable cause) {
        super(cause);
    }

    protected RemotingSystemException wrapForRethrow() {
        return new RemotingSystemException(this);
    }

    private static final long serialVersionUID = 1L;
}
