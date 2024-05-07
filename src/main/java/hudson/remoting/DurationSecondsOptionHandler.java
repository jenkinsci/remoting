package hudson.remoting;

import java.time.Duration;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

/**
 * Parses a positive number of seconds into a {@link Duration}.
 */
public class DurationSecondsOptionHandler extends OptionHandler<Duration> {
    public DurationSecondsOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super Duration> setter) {
        super(parser, option, setter);
    }

    @Override
    public int parseArguments(Parameters params) throws CmdLineException {
        var seconds = Integer.parseInt(params.getParameter(0));
        if (seconds <= 0) {
            throw new CmdLineException(owner, option.toString() + " takes a strictly positive duration");
        }
        setter.addValue(Duration.ofSeconds(seconds));
        return 1;
    }

    @Override
    public String getDefaultMetaVariable() {
        return "SECONDS";
    }

    @Override
    protected String print(Duration v) {
        return v == null ? null : Long.toString(v.getSeconds());
    }
}
