package org.jenkinsci.remoting.util;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import org.junit.Test;

public class DurationStyleTest {
    @Test
    public void typical() {
        assertEquals(Duration.ofSeconds(1), DurationStyle.detectAndParse("1s"));
        assertEquals(Duration.ofMinutes(2), DurationStyle.detectAndParse("2m"));
        assertEquals(Duration.ofHours(3), DurationStyle.detectAndParse("3h"));
        assertEquals(Duration.ofDays(4), DurationStyle.detectAndParse("4d"));
    }

    @Test
    public void negative() {
        assertEquals(Duration.ofSeconds(1).negated(), DurationStyle.detectAndParse("-1s"));
    }
}
