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
package org.jenkinsci.remoting.util;

import java.nio.charset.Charset;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Local implementation of standard charsets as the remoting library currently needs to be compiled against Java 6
 * signatures.
 *
 * @since FIXME
 */
// TODO replace with StandardCharsets once Java 7
@Restricted(NoExternalUse.class)
public final class Charsets {
    /**
     * Utility class.
     */
    private Charsets() {
        throw new IllegalAccessError("Utility class");
    }

    /**
     * Seven-bit ASCII.
     */
    public static final Charset US_ASCII = Charset.forName("US-ASCII");
    /**
     * ISO Latin Alphabet 1.
     */
    public static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");
    /**
     * UTF-8.
     */
    public static final Charset UTF_8 = Charset.forName("UTF-8");
    /**
     * Big-endian byte order UTF-16.
     */
    public static final Charset UTF_16BE = Charset.forName("UTF-16BE");
    /**
     * Little-endian byte order UTF-16.
     */
    public static final Charset UTF_16LE = Charset.forName("UTF-16LE");
    /**
     * Byte order mark detected UTF-16.
     */
    public static final Charset UTF_16 = Charset.forName("UTF-16");

}
