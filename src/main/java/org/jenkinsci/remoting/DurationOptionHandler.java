package org.jenkinsci.remoting;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
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
        return formatDuration(v);
    }

    private static String formatDuration(Duration d) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        long days = d.toDays();
        if (days > 0) {
            first = formatDurationPart(true, sb, days, "day");
            d = d.minus(days, ChronoUnit.DAYS);
        }
        long hours = d.toHours();
        if (hours > 0) {
            first = formatDurationPart(first, sb, hours, "hour");
            d = d.minus(hours, ChronoUnit.HOURS);
        }
        long minutes = d.toMinutes();
        if (minutes > 0) {
            first = formatDurationPart(first, sb, minutes, "minute");
            d = d.minus(minutes, ChronoUnit.MINUTES);
        }
        long seconds = d.getSeconds() ;
        if (seconds > 0) {
            formatDurationPart(first, sb, seconds, "second");
        }
        return sb.toString();
    }

    private static boolean formatDurationPart(boolean first, StringBuilder sb, long amount, String unit) {
        if (!first) {
            sb.append(", ");
        } else {
            first = false;
        }
        sb.append(amount).append(" ").append(unit).append(amount > 1 ? "s" : "");
        return first;
    }
}
