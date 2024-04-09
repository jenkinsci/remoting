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

/**
 * An exception to flag that the connection has been rejected and no further connection attempts should be made.
 * @deprecated Does not actually do what it claims; only affects logging levels.
 * @since 3.0
 */
@Deprecated
public class PermanentConnectionRefusalException extends ConnectionRefusalException {
    public PermanentConnectionRefusalException() {
        super();
    }

    public PermanentConnectionRefusalException(String message) {
        super(message);
    }

    public PermanentConnectionRefusalException(String message, Object... args) {
        super(message, args);
    }

    public PermanentConnectionRefusalException(Throwable cause, String message, Object... args) {
        super(cause, message, args);
    }

    public PermanentConnectionRefusalException(String message, Throwable cause) {
        super(message, cause);
    }

    public PermanentConnectionRefusalException(Throwable cause) {
        super(cause);
    }
}
