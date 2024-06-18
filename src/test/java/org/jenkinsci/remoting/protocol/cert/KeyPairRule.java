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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

public abstract class KeyPairRule<PUB extends PublicKey, PRIV extends PrivateKey> implements TestRule {
    private final String id;
    private KeyPair keys;

    protected KeyPairRule() {
        this("");
    }

    protected KeyPairRule(String id) {
        this.id = id;
    }

    public PUB getPublic() {
        return (PUB) keys.getPublic();
    }

    public PRIV getPrivate() {
        return (PRIV) keys.getPrivate();
    }

    @Override
    public Statement apply(final Statement base, Description description) {
        Skip skip = description.getAnnotation(Skip.class);
        if (skip != null
                && (skip.value().length == 0 || Arrays.asList(skip.value()).contains(id))) {
            return base;
        }
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                keys = generateKeyPair();
                try {
                    base.evaluate();
                } finally {
                    keys = null;
                }
            }
        };
    }

    protected abstract KeyPair generateKeyPair() throws NoSuchAlgorithmException, InvalidAlgorithmParameterException;

    /**
     * Indicate the the rule should be skipped for the annotated tests.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface Skip {
        String[] value() default {};
    }
}
