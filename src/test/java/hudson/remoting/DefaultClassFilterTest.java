/*
 * The MIT License
 *
 * Copyright (c) 2015 CloudBees, Inc.
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

import static hudson.remoting.DefaultClassFilterTest.BlackListMatcher.blacklisted;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.core.Every.everyItem;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.IOUtils;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DefaultClassFilterTest {

    /** Some classes that should be matched by the default class filter */
    private static final List<String> defaultBadClasses = Arrays.asList("org.codehaus.groovy.runtime.Bob",
                                    "org.apache.commons.collections.functors.Wibble", "org.apache.xalan.Bogus",
                                    "com.sun.org.apache.xalan.bogus", "org.springframework.core.SomeClass", 
                                    "org.springframework.wibble.ExceptionHandler");
    /** Some classes that should not be matched by the default class filter */
    private static final List<String> defaultOKClasses = Arrays.asList("java.lang.String", "java.lang.Object",
                                    "java.util.ArrayList", "org.springframework.core.NestedRuntimeException",
                                    "org.springframework.a.b.c.yada.SomeSuperException");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @After
    public void clearProperty() throws Exception {
        setOverrideProperty(null);
    }

    /**
     * Checks that the defaults are loaded when no override is provided.
     */
    @Test
    public void testDefaultsNoOverride() {
        assertThat("Default blacklist is not blacklisting some classes", defaultBadClasses,
                                        everyItem(is(blacklisted())));
        assertThat("Default blacklist is not allowing some classes", defaultOKClasses,
                                        everyItem(is(not(blacklisted()))));
    }

    /**
     * Checks that the overrides are loaded when the property is provided and the file exists.
     */
    @Test
    public void testDefaultsOverrideExists() throws Exception {
        List<String> badClasses = Arrays.asList("eric.Clapton", "john.winston.ono.Lennon", "jimmy.Page");
        File f = folder.newFile("overrides.txt");
        FileOutputStream fos = new FileOutputStream(f);
        try {
            for (String s : badClasses) {
                IOUtils.write(s, fos);
                IOUtils.write("\n", fos);
            }
        } finally {
            fos.close();
        }
        setOverrideProperty(f.getAbsolutePath());
        assertThat("Default blacklist should not be used", defaultBadClasses, everyItem(is(not(blacklisted()))));
        assertThat("Custom blacklist should be used", badClasses, everyItem(is(blacklisted())));
        assertThat("Custom blacklist is not allowing some classes", defaultOKClasses, everyItem(is(not(blacklisted()))));
    }

    /**
     * Checks that if given an invalid pattern in the overrides then the defaults are used.
     */
    @Test(expected=Error.class)
    public void testDefaultsAreUsedIfOverridesAreGarbage() throws Exception {
        List<String> badClasses = Arrays.asList("Z{100,0}" /* min > max for repetition */);
        File f = folder.newFile("overrides.txt");
        FileOutputStream fos = new FileOutputStream(f);
        try {
            for (String s : badClasses) {
                IOUtils.write(s, fos);
                IOUtils.write("\n", fos);
            }
        } finally {
            fos.close();
        }
        setOverrideProperty(f.getAbsolutePath());

        ClassFilter.createDefaultInstance();
    }

    /**
     * Checks that the defaults are loaded when the override property is provided and the file does not exist.
     */
    @Test(expected=Error.class)
    public void testDefaultsRemainWhenOverrideDoesExists() throws Exception {
        setOverrideProperty(folder.getRoot().toString()
                + "/DO_NOT_CREATE_THIS_FILE_OR_ELSE_BAD_THINGS_WILL_HAPPEN_TO_YOU");
        ClassFilter.createDefaultInstance();
    }

    public static void setOverrideProperty(String value) throws Exception {
        if (value == null) {
            System.clearProperty(hudson.remoting.ClassFilter.FILE_OVERRIDE_LOCATION_PROPERTY);
        } else {
            System.setProperty(hudson.remoting.ClassFilter.FILE_OVERRIDE_LOCATION_PROPERTY, value);
        }
    }

    /** Simple hamcrest matcher that checks if the provided className is blacklisted. */
    static class BlackListMatcher extends org.hamcrest.BaseMatcher<String> {

        public void describeMismatch(Object item, Description description) {
            description.appendValue(item).appendText(" was not blacklisted");
        }

        public static Matcher<String> blacklisted() {
            return new BlackListMatcher();
        }

        public boolean matches(Object item) {
            try {
                ClassFilter.createDefaultInstance().check(item.toString());
                return Boolean.FALSE;
            } catch (ClassFilter.ClassFilterException ex) {
                throw new IllegalStateException("Failed to initialize the default class filter", ex);
            } catch (SecurityException sex) {
                return Boolean.TRUE;
            }
        }

        public void describeTo(Description description) {
            description.appendText("blacklisted");
        }
    }

}
