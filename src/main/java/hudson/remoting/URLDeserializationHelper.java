/*
 * The MIT License
 *
 * Copyright (c) 2018, CloudBees, Inc.
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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * SECURITY-637, this helper wraps the URL into a "safe" version if the url has a non-empty host
 * and the JVM configuration is standard.
 *
 * Essentially the wrap does not provide the same logic for {@link URLStreamHandler#hashCode(URL)}
 * and {@link URLStreamHandler#equals(URL, URL)} but a version that use directly the {@code String} representation
 * instead of requesting the DNS to have name equivalence.
 *
 * @since 3.25
 */
public class URLDeserializationHelper {
    // escape hatch for SECURITY-637 to keep legacy behavior
    @SuppressFBWarnings(value = "MS_SHOULD_BE_FINAL", justification = "Accessible via System Groovy Scripts")
    private static boolean AVOID_URL_WRAPPING =
            Boolean.getBoolean(URLDeserializationHelper.class + ".avoidUrlWrapping");

    private static final SafeURLStreamHandler SAFE_HANDLER = new SafeURLStreamHandler();

    /**
     * Wraps the given URL into a "safe" version against deserialization attack if the url has a non-empty host
     * and the JVM configuration is standard.
     */
    public static @NonNull URL wrapIfRequired(@NonNull URL url) throws IOException {
        if (AVOID_URL_WRAPPING) {
            // legacy behavior
            return url;
        }

        if (url.getHost() == null || url.getHost().isEmpty()) {
            // default equals/hashcode are not vulnerable in case the host is null or empty
            return url;
        }

        return new URL(null, url.toString(), SAFE_HANDLER);
    }

    private static class SafeURLStreamHandler extends URLStreamHandler {
        @Override
        @SuppressFBWarnings(
                value = "URLCONNECTION_SSRF_FD",
                justification = "Used for safely handling URLs, not for opening a connection.")
        protected URLConnection openConnection(URL u, Proxy p) throws IOException {
            return new URL(u.toString()).openConnection(p);
        }

        @Override
        @SuppressFBWarnings(
                value = "URLCONNECTION_SSRF_FD",
                justification = "Used for safely handling URLs, not for opening a connection.")
        protected URLConnection openConnection(URL u) throws IOException {
            return new URL(u.toString()).openConnection();
        }

        // actual correction is here (hashCode + equals), we avoid requesting the DNS for the hostname

        @Override
        protected int hashCode(URL u) {
            return u.toExternalForm().hashCode();
        }

        @Override
        protected boolean equals(URL u1, URL u2) {
            return u1.toExternalForm().equals(u2.toExternalForm());
        }
    }
}
