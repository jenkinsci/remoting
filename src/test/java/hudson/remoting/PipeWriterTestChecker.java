package hudson.remoting;

/**
 * @author Kohsuke Kawaguchi
 */
public interface PipeWriterTestChecker {
    void assertSlowStreamNotTouched();

    void assertSlowStreamTouched();
}
