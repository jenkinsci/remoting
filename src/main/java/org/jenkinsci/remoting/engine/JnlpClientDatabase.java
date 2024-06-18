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
package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.cert.X509Certificate;

/**
 * Represents a database of clients that are permitted to connect.
 *
 * @since 3.0
 */
public abstract class JnlpClientDatabase {

    /**
     * Check if the supplied client name exists.
     * @param clientName the client name.
     * @return {@code true} if and only if the named client exists.
     */
    public abstract boolean exists(String clientName);

    /**
     * Gets the secret for the supplied client name.
     * @param clientName the client name.
     * @return the secret or {@code null}. Should not return {@code null} if {@link #exists(String)} but this may occur
     * if there is a race between establishing a connection and the client being removed from the database.
     */
    public abstract String getSecretOf(@NonNull String clientName);

    /**
     * Performs client certificate validation.
     * @param clientName the client name.
     * @param certificate the certificate.
     * @return the validation.
     */
    @NonNull
    public ValidationResult validateCertificate(@NonNull String clientName, @NonNull X509Certificate certificate) {
        return ValidationResult.UNCHECKED;
    }

    /**
     * The types of certificate validation results.
     */
    public enum ValidationResult {
        /**
         * The certificate is invalid, reject the connection.
         */
        INVALID,
        /**
         * The certificate was not checked, fall back to secret validation.
         */
        UNCHECKED,
        /**
         * The certificate is valid, but check the secret also.
         */
        REVERIFY_SECRET,
        /**
         * The certificate is valid and proven to originate from the named client, skip secret validation.
         */
        IDENTITY_PROVED
    }
}
