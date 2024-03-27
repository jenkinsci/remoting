/*
 * The MIT License
 *
 * Copyright (c) 2023, CloudBees, Inc.
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

import java.security.SecureRandom;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.Random;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Retry-related utility methods. Used in place of a library like <a
 * href="https://failsafe.dev/">Failsafe</a> to minimize external third-party dependencies.
 */
@Restricted(NoExternalUse.class)
public class RetryUtils {

    private static final Random RANDOM = new SecureRandom();

    // Suppress default constructor for noninstantiability
    private RetryUtils() {
        throw new AssertionError();
    }

    /**
     * Get the retry duration based on the CLI arguments.
     */
    public static Duration getDuration(int delay, double jitterFactor, int jitter) {
        if (jitterFactor != 0) {
            double randomFactor = 1 + (1 - RANDOM.nextDouble() * 2) * jitterFactor;
            return Duration.ofMillis((long) (Duration.ofSeconds(delay).toMillis() * randomFactor));
        } else if (jitter != 0) {
            double randomAddend =
                    (1 - RANDOM.nextDouble() * 2) * Duration.ofSeconds(jitter).toMillis();
            return Duration.ofMillis((long) (Duration.ofSeconds(delay).toMillis() + randomAddend));
        } else {
            return Duration.ofSeconds(delay);
        }
    }

    public static String formatDuration(Duration duration) {
        return NumberFormat.getNumberInstance().format(duration.toMillis() / 1000.0);
    }
}
