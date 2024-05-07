package org.jenkinsci.remoting;

import java.time.Duration;
import org.jenkinsci.remoting.util.DurationFormatter;
import org.jenkinsci.remoting.util.DurationStyle;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

/**
 * Parses a string like 1s, 1m, 1d into a {@link Duration}.
 */
public class DurationOptionHandler extends OptionHandler<Duration> {
    public DurationOptionHandler(CmdLineParser parser, OptionDef option, Setter<? super Duration> setter) {
        super(parser, option, setter);
    }

    @Override
    public int parseArguments(Parameters params) throws CmdLineException {
        setter.addValue(DurationStyle.detectAndParse(params.getParameter(0)));
        return 1;
    }

    @Override
    public String getDefaultMetaVariable() {
        return "DURATION";
    }

    @Override
    protected String print(Duration v) {
        return DurationFormatter.format(v);
    }

}
