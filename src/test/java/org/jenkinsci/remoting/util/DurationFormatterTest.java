package org.jenkinsci.remoting.util;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import org.junit.Test;

public class DurationFormatterTest {
    @Test
    public void typical() {
        assertEquals("1 second", DurationFormatter.format(Duration.ofSeconds(1)));
        assertEquals("2 seconds", DurationFormatter.format(Duration.ofSeconds(2)));
        assertEquals(
                "1 day, 2 seconds", DurationFormatter.format(Duration.ofDays(1).plus(Duration.ofSeconds(2))));
        assertEquals(
                "2 days, 3 hours, 2 seconds",
                DurationFormatter.format(
                        Duration.ofDays(2).plus(Duration.ofHours(3)).plus(Duration.ofSeconds(2))));
        assertEquals(
                "2 days, 3 hours, 1 minute, 2 seconds",
                DurationFormatter.format(Duration.ofDays(2)
                        .plus(Duration.ofHours(3))
                        .plus(Duration.ofMinutes(1))
                        .plus(Duration.ofSeconds(2))));
    }
}
