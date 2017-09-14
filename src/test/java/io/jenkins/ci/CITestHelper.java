/*
 *
 * The MIT License
 *
 * Copyright (c) 2017 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 *
 */

package io.jenkins.ci;

import org.junit.Assume;
import org.junit.AssumptionViolatedException;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Test helpers for running on ci.jenkins.io.
 * This is just a test helper class, which will be removed in the future.
 *
 * @author Oleg Nenashev.
 * @since TODO
 */
@Restricted(NoExternalUse.class)
public class CITestHelper {

    public static boolean isRunningOnCI() {
        return Boolean.getBoolean("io.jenkinci.ci.skipTestsUnstableOnCI");
    }

    /**
     * Skips test if it should not be executed in the CI mode
     * @throws AssumptionViolatedException Skips the test
     */
    public static void skipIfRunningOnCI(String why) throws AssumptionViolatedException {
        Assume.assumeFalse("Skipping because 'io.jenkinci.ci.skipTestsUnstableOnCI' is set. Reason: " + why,
                isRunningOnCI());
    }

}
