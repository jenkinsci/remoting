/*
 * The MIT License
 *
 * Copyright 2023 CloudBees, Inc.
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

package hudson.remoting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.junit.Test;

public class ProxyExceptionTest {

    @Test
    public void breaksCyclesInCauses() {
        Exception cyclic1 = new Exception("cyclic1");
        Exception cyclic2 = new Exception("cyclic2", cyclic1);
        cyclic1.initCause(cyclic2);
        ProxyException pe1 = new ProxyException(cyclic1);
        assertThat(pe1.getMessage(), is(cyclic1.toString()));
        assertThat(pe1.getStackTrace(), is(cyclic1.getStackTrace()));
        ProxyException pe2 = pe1.getCause();
        assertThat(pe2.getMessage(), is(cyclic2.toString()));
        assertThat(pe2.getStackTrace(), is(cyclic2.getStackTrace()));
        assertThat(pe2.getCause(), is(nullValue()));
    }

    @Test
    public void breaksCyclesInSuppressedExceptions() {
        Exception cyclic1 = new Exception("cyclic1");
        Exception cyclic2 = new Exception("cyclic2");
        cyclic1.addSuppressed(cyclic2);
        cyclic2.addSuppressed(cyclic1);
        ProxyException pe1 = new ProxyException(cyclic1);
        assertThat(pe1.getMessage(), is(cyclic1.toString()));
        assertThat(pe1.getStackTrace(), is(cyclic1.getStackTrace()));
        ProxyException pe2 = (ProxyException) pe1.getSuppressed()[0];
        assertThat(pe2.getMessage(), is(cyclic2.toString()));
        assertThat(pe2.getStackTrace(), is(cyclic2.getStackTrace()));
        assertThat(pe2.getSuppressed(), is(emptyArray()));
    }
}
