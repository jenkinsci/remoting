/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.Channel;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.CodeSource;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Logger;

/**
 * Issues warnings about attempts to (de-)serialize anonymous, local, or synthetic classes.
 * @see <a href="https://jenkins.io/redirect/serialization-of-anonymous-classes/">More information</a>
 */
public class AnonymousClassWarnings {

    private static final Logger LOGGER = Logger.getLogger(AnonymousClassWarnings.class.getName());
    private static final Map<Class<?>, Boolean> checked = new WeakHashMap<>();

    /**
     * Checks a class which is being either serialized or deserialized.
     * A warning will only be printed once per class per JVM session.
     */
    public static void check(@NonNull Class<?> clazz) {
        synchronized (checked) {
            if (checked.put(clazz, true) != null) {
                return;
            }
        }
        Channel channel = Channel.current();
        if (channel == null) {
            doCheck(clazz);
        } else {
            // May not call methods like Class#isAnonymousClass synchronously, since these can in turn trigger remote
            // class loading.
            try {
                channel.executor.submit(() -> doCheck(clazz));
            } catch (RejectedExecutionException x) {
                // never mind, we tried
            }
        }
    }

    private static void doCheck(@NonNull Class<?> c) {
        if (Enum.class.isAssignableFrom(
                c)) { // e.g., com.cloudbees.plugins.credentials.CredentialsScope$1 ~ CredentialsScope.SYSTEM
            // ignore, enums serialize specially
        } else if (c.isAnonymousClass()) { // e.g., pkg.Outer$1
            warn(c, "anonymous");
        } else if (c.isLocalClass()) { // e.g., pkg.Outer$1Local
            warn(c, "local");
        } else if (c.isSynthetic()) { // e.g., pkg.Outer$$Lambda$1/12345678
            warn(c, "synthetic");
        }
    }

    private static void warn(@NonNull Class<?> c, String kind) {
        String name = c.getName();
        String codeSource = codeSource(c);
        if (codeSource == null) {
            LOGGER.warning("Attempt to (de-)serialize " + kind + " class " + name
                    + "; see: https://jenkins.io/redirect/serialization-of-anonymous-classes/");
        } else {
            // most easily tracked back to source using javap -classpath <location> -l '<name>'
            LOGGER.warning("Attempt to (de-)serialize " + kind + " class " + name + " in " + codeSource
                    + "; see: https://jenkins.io/redirect/serialization-of-anonymous-classes/");
        }
    }

    private static @CheckForNull String codeSource(@NonNull Class<?> c) {
        CodeSource cs = c.getProtectionDomain().getCodeSource();
        if (cs == null) {
            return null;
        }
        URL loc = cs.getLocation();
        if (loc == null) {
            return null;
        }
        return loc.toString();
    }

    /**
     * Like {@link ObjectOutputStream#ObjectOutputStream(OutputStream)} but applies {@link #check} when writing classes.
     */
    public static @NonNull ObjectOutputStream checkingObjectOutputStream(@NonNull OutputStream outputStream)
            throws IOException {
        return new ObjectOutputStream(outputStream) {
            @Override
            protected void annotateClass(Class<?> c) throws IOException {
                check(c);
                super.annotateClass(c);
            }
        };
    }

    private AnonymousClassWarnings() {}
}
