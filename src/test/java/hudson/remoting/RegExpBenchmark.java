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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("This is not a test just a benchmark and is here for ease of running")
class RegExpBenchmark {

    private static final Pattern P_1 = Pattern.compile("^org\\.codehaus\\.groovy\\.runtime\\..*");
    private static final Pattern P_2 = Pattern.compile("^org\\.apache\\.commons\\.collections\\.functors\\..*");
    private static final Pattern P_3 = Pattern.compile("^.*org\\.apache\\.xalan\\..*");

    private static final Pattern P_4 = Pattern.compile(
            "^(?:(?:org\\.(?:codehaus\\.groovy\\.runtime|apache\\.commons\\.collections\\.functors))|.*?org\\.apache\\.xalan)\\..*");

    private static final String S_1 = "org.codehaus.groovy.runtime.";
    private static final String S_2 = "org.apache.commons.collections.functors.";
    private static final String S_3 = "org.apache.xalan.";

    @Test
    void repeatedBenchMark() throws Exception {
        for (int i = 0; i < 10; i++) {
            benchmark();
            System.gc();
            System.gc();
            System.gc();
        }
    }

    @Test
    void benchmark() throws Exception {
        System.out.println("there are " + getAllRTClasses().size());

        List<String> classes = getAllRTClasses();
        final long startRegExp = System.nanoTime();
        final List<String> matchesRegExp = checkClassesRegExp(classes);
        final long durationRegexpNanos = System.nanoTime() - startRegExp;

        System.gc();
        System.gc();
        System.gc();

        // make sure we use new Strings each time so that hotpsot does not do funky caching (after all the strings we
        // will be testing will come from the stream and be new).
        classes = getAllRTClasses();
        final long startSingleRegExp = System.nanoTime();
        final List<String> matchesSingleRegExp = checkClassesSingleRegExp(classes);
        final long durationSingleRegexpNanos = System.nanoTime() - startSingleRegExp;
        System.gc();
        System.gc();
        System.gc();

        // make sure we use new Strings each time so that hotpsot does not do funky caching (after all the strings we
        // will be testing will come from the stream and be new).
        classes = getAllRTClasses();
        final long startString = System.nanoTime();
        final List<String> matchesString = checkClassesString(classes);
        final long durationStringNanos = System.nanoTime() - startString;

        System.out.printf(
                Locale.ENGLISH,
                "%-13s: %d blacklisted classes in %9dns.  Average class check time is %dns%n",
                "RegExp ",
                matchesRegExp.size(),
                durationRegexpNanos,
                durationRegexpNanos / classes.size());
        System.out.printf(
                Locale.ENGLISH,
                "%-13s: %d blacklisted classes in %9dns.  Average class check time is %dns%n",
                "SingleRegExp ",
                matchesSingleRegExp.size(),
                durationSingleRegexpNanos,
                durationSingleRegexpNanos / classes.size());
        System.out.printf(
                Locale.ENGLISH,
                "%-13s: %d blacklisted classes in %9dns.  Average class check time is %dns%n",
                "String ",
                matchesString.size(),
                durationStringNanos,
                durationStringNanos / classes.size());

        System.out.println("Regular Expression is " + durationRegexpNanos / durationStringNanos + " times slower");
        System.out.println(
                "Single Regular Expression is " + durationSingleRegexpNanos / durationStringNanos + " times slower\n");
    }

    private List<String> checkClassesRegExp(List<String> classnames) {
        List<String> blacklistedClasses = new ArrayList<>();
        for (String s : classnames) {
            if (P_1.matcher(s).matches()
                    || P_2.matcher(s).matches()
                    || P_3.matcher(s).matches()) {
                // something with a side effect
                blacklistedClasses.add(s);
            }
        }
        return blacklistedClasses;
    }

    private List<String> checkClassesSingleRegExp(List<String> classnames) {
        List<String> blacklistedClasses = new ArrayList<>();
        for (String s : classnames) {
            if (P_4.matcher(s).matches()) {
                // something with a side effect
                blacklistedClasses.add(s);
            }
        }
        return blacklistedClasses;
    }

    private List<String> checkClassesString(List<String> classnames) {
        List<String> blacklistedClasses = new ArrayList<>();
        for (String s : classnames) {
            if (s.startsWith(S_1) || s.startsWith(S_2) || s.contains(S_3)) {
                // something with a side effect
                blacklistedClasses.add(s);
            }
        }
        return blacklistedClasses;
    }

    private List<String> getAllRTClasses() throws Exception {
        List<String> classes = new ArrayList<>();
        // Object.class.getProtectionDomain().getCodeSource() returns null :(
        String javaHome = System.getProperty("java.home");
        JarFile jf = new JarFile(javaHome + "/lib/rt.jar");
        for (JarEntry je : Collections.list(jf.entries())) {
            if (!je.isDirectory() && je.getName().endsWith(".class")) {
                String name = je.getName().replace('/', '.');
                // remove the .class
                name = name.substring(0, name.length() - 6);
                classes.add(name);
            }
        }
        jf.close();
        // add in a couple from xalan and commons just for testing...
        classes.add("org.apache.commons.collections.functors.EvilClass");
        classes.add("org.codehaus.groovy.runtime.IWIllHackYou");
        classes.add("org.apache.xalan.YouAreOwned");
        return classes;
    }
}
