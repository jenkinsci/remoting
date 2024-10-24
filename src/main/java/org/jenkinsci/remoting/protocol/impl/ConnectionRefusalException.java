/*
 * The MIT License
 *
 * Copyright (c) 2016, Stephen Connolly, CloudBees, Inc.
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
package org.jenkinsci.remoting.protocol.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.IllegalFormatException;

/**
 * An exception to flag that the connection has been refused.
 *
 * @since 3.0
 * @see PermanentConnectionRefusalException for a permanent rejection of the connection.
 */
public class ConnectionRefusalException extends IOException {

    public ConnectionRefusalException() {}

    public ConnectionRefusalException(String message) {
        super(message);
    }

    /**
     * Constructs an {@code ConnectionRefusalException} with the specified detail message formatted using
     * {@link String#format(String, Object...)}.
     *
     * @param message The detail message format string.
     * @param args    Arguments referenced by the format specifiers in the format
     *                string.
     * @throws IllegalFormatException If a format string contains an illegal syntax, a format
     *                                          specifier that is incompatible with the given arguments,
     *                                          insufficient arguments given the format string, or other
     *                                          illegal conditions.
     */
    public ConnectionRefusalException(String message, Object... args) {
        this(null, message, args);
    }

    /**
     * Constructs an {@code ConnectionRefusalException} with the specified detail message formatted using
     * {@link String#format(String, Object...)}.
     *
     * @param cause
     *        The cause (which is saved for later retrieval by the
     *        {@link #getCause()} method).  (A null value is permitted,
     *        and indicates that the cause is nonexistent or unknown.)
     * @param message The detail message format string.
     * @param args    Arguments referenced by the format specifiers in the format
     *                string.
     * @throws IllegalFormatException If a format string contains an illegal syntax, a format
     *                                          specifier that is incompatible with the given arguments,
     *                                          insufficient arguments given the format string, or other
     *                                          illegal conditions.
     */
    @SuppressFBWarnings(
            value = "FORMAT_STRING_MANIPULATION",
            justification = "This is only used with String conversion to hex string.")
    public ConnectionRefusalException(Throwable cause, String message, Object... args) {
        super(String.format(message, args), cause);
    }

    public ConnectionRefusalException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConnectionRefusalException(Throwable cause) {
        super(cause);
    }
}
