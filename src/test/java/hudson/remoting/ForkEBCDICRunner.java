package hudson.remoting;

import java.util.List;

/**
 * {@link ForkRunner} with an ASCII incompatible encoding.
 */
public class ForkEBCDICRunner extends ForkRunner {
    @Override
    protected List<String> buildCommandLine() {
        List<String> r = super.buildCommandLine();
        r.add(0, "-Dfile.encoding=CP037");
        return r;
    }

    @Override
    public String getName() {
        return "forkEBCDIC";
    }
}
