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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Creates an destroys {@link IOHub} instances for tests.
 */
public class IOHubExtension implements BeforeEachCallback, AfterEachCallback {

    private final String id;
    /**
     * The {@link ExecutorService} for the current test.
     */
    private ExecutorService executorService;
    /**
     * The {@link IOHub} for the current test.
     */
    private IOHub selector;

    public IOHubExtension() {
        this("");
    }

    public IOHubExtension(String id) {
        this.id = id;
    }

    /**
     * Retrieves the {@link ExecutorService} for this test.
     *
     * @return the {@link ExecutorService} for this test
     */
    public ExecutorService executorService() {
        return executorService;
    }

    /**
     * Retrieves the {@link IOHub} for this test.
     *
     * @return the {@link IOHub} for this test
     */
    public IOHub hub() {
        return selector;
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        final AtomicInteger counter = new AtomicInteger();
        executorService = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2 - 1,
                r -> new Thread(
                        r,
                        String.format(
                                "%s%s-%d",
                                context.getTestMethod().orElseThrow().getName(),
                                id == null || id.isEmpty() ? "" : "-" + id,
                                counter.incrementAndGet())));
        selector = IOHub.create(executorService);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        IOUtils.closeQuietly(selector);
        selector = null;

        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }
}
