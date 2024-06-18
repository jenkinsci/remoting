/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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
package org.jenkinsci.remoting.protocol;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public class RepeatRule implements TestRule {

    private static final Logger LOGGER = Logger.getLogger(RepeatRule.class.getName());

    @Override
    public Statement apply(final Statement base, final Description description) {
        final Repeat repeat = description.getAnnotation(Repeat.class);
        return repeat == null || (repeat.value() <= 0 && repeat.stopAfter() <= 0L)
                ? base
                : new Statement() {
                    @Override
                    public void evaluate() throws Throwable {
                        long nextProgress = System.currentTimeMillis() + 10000;
                        int lastLoopCount = 0;
                        int maxLoops;
                        long stopNanos;
                        String text;
                        if (repeat.stopAfter() <= 0L) {
                            stopNanos = Long.MAX_VALUE;
                            if (repeat.value() <= 0) {
                                maxLoops = 1;
                                text = "once";
                            } else {
                                maxLoops = repeat.value();
                                text = String.format("%d times", maxLoops);
                            }
                        } else {
                            if (repeat.value() <= 0) {
                                maxLoops = Integer.MAX_VALUE;
                                text = String.format(
                                        "for %d %s",
                                        repeat.stopAfter(),
                                        repeat.stopAfterUnits().name());
                            } else {
                                maxLoops = repeat.value();
                                text = String.format(
                                        "for %d times or %d %s",
                                        maxLoops,
                                        repeat.stopAfter(),
                                        repeat.stopAfterUnits().name());
                            }
                            stopNanos = System.currentTimeMillis()
                                    + repeat.stopAfterUnits().toMillis(repeat.stopAfter());
                        }
                        int loopCount;
                        for (loopCount = 0;
                                loopCount < maxLoops && System.currentTimeMillis() < stopNanos;
                                loopCount++) {
                            base.evaluate();
                            if (System.currentTimeMillis() > nextProgress) {
                                double loopsPerSec = (loopCount - lastLoopCount + 1) / 10.0;
                                LOGGER.log(
                                        Level.INFO,
                                        "Repeating {0} {1} at {2,number,0.0} runs per second, {3,number} done",
                                        new Object[] {description.getDisplayName(), text, loopsPerSec, loopCount + 1});
                                lastLoopCount = loopCount;
                                nextProgress = System.currentTimeMillis() + 10000;
                                System.gc();
                            }
                        }
                        if (repeat.stopAfter() > 0) {
                            LOGGER.log(Level.INFO, "Repeated {0} {1,number} times", new Object[] {
                                description.getDisplayName(), loopCount
                            });
                        }
                    }
                };
    }
}
