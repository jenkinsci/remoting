/*
 * The MIT License
 *
 * Copyright (c) 2024, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.remoting.util;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Formats a {@link Duration} into a human-readable string.
 */
@Restricted(NoExternalUse.class)
public final class DurationFormatter {
    private DurationFormatter() {}

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
