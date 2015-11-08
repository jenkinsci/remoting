package hudson.remoting;

/**
 * Subset of {@link ChannelRunner} that provides in-memory access
 * to the other side, not just this side returned by {@link #start()}
 *
 * @author Kohsuke Kawaguchi
 */
public interface DualSideChannelRunner extends ChannelRunner {
    Channel getOtherSide();
}
