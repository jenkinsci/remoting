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
package org.jenkinsci.remoting.protocol.cert;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

public abstract class KeyPairExtension<PUB extends PublicKey, PRIV extends PrivateKey>
        implements BeforeAllCallback, AfterAllCallback {

    private final String id;
    private KeyPair keys;

    protected KeyPairExtension() {
        this("");
    }

    protected KeyPairExtension(String id) {
        this.id = id;
    }

    public PUB getPublic() {
        return (PUB) keys.getPublic();
    }

    public PRIV getPrivate() {
        return (PRIV) keys.getPrivate();
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        keys = generateKeyPair();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        keys = null;
    }

    protected abstract KeyPair generateKeyPair() throws NoSuchAlgorithmException, InvalidAlgorithmParameterException;
}
