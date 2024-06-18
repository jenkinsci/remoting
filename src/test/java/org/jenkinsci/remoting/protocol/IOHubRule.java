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
package org.jenkinsci.remoting.protocol;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.io.IOUtils;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Creates an destroys {@link IOHub} instances for tests.
 */
public class IOHubRule implements TestRule {
    private final String id;
    /**
     * The {@link ExecutorService} for the current test.
     */
    private ExecutorService executorService;
    /**
     * The {@link IOHub} for the current test.
     */
    private IOHub selector;

    public IOHubRule() {
        this("");
    }

    public IOHubRule(String id) {
        this.id = id;
    }

    /**
     * Retrieves the {@link ExecutorService} for this test.
     *
     * @return the {@link ExecutorService} for this test or {@code null} if the test is annotated with {@link Skip}
     */
    public ExecutorService executorService() {
        return executorService;
    }

    /**
     * Retrieves the {@link IOHub} for this test.
     *
     * @return the {@link IOHub} for this test or {@code null} if the test is annotated with {@link Skip}
     */
    public IOHub hub() {
        return selector;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Statement apply(final Statement base, final Description description) {
        Skip skip = description.getAnnotation(Skip.class);
        if (skip != null
                && (skip.value().length == 0 || Arrays.asList(skip.value()).contains(id))) {
            return base;
        }
        final AtomicInteger counter = new AtomicInteger();
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                executorService = Executors.newFixedThreadPool(
                        Runtime.getRuntime().availableProcessors() * 2 - 1,
                        r -> new Thread(
                                r,
                                String.format(
                                        "%s%s-%d",
                                        description.getDisplayName(),
                                        id == null || id.isEmpty() ? "" : "-" + id,
                                        counter.incrementAndGet())));
                selector = IOHub.create(executorService);
                try {
                    base.evaluate();
                } finally {
                    IOUtils.closeQuietly(selector);
                    selector = null;
                    executorService.shutdownNow();
                    executorService = null;
                }
            }
        };
    }

    /**
     * Indicate the the rule should be skipped for the annotated tests.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    public @interface Skip {
        String[] value() default {};
    }
}
