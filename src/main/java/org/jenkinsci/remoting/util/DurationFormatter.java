package org.jenkinsci.remoting.util;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Formats a {@link Duration} into a human-readable string.
 */
public final class DurationFormatter {
    private DurationFormatter(){}

    public static String format(Duration d) {
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
        long seconds = d.getSeconds();
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
