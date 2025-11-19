/*
 * The MIT License
 *
 * Copyright (c) 2017 CloudBees, Inc.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.engine.WorkDirManager;
import org.jenkinsci.remoting.engine.WorkDirManagerExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.jvnet.hudson.test.Issue;

/**
 * Tests of {@link Engine}
 * @author Oleg Nenashev
 */
class EngineTest {

    private static final Logger LOGGER = Logger.getLogger(EngineTest.class.getName());

    private static final String SECRET_KEY = "Hello, world!";
    private static final String AGENT_NAME = "testAgent";
    private List<URL> jenkinsUrls;

    @TempDir
    private File tmpDir;

    @RegisterExtension
    private final WorkDirManagerExtension mgr = new WorkDirManagerExtension();

    @BeforeEach
    void beforeEach() throws Exception {
        jenkinsUrls = List.of(new URL("http://my.jenkins.not.existent"));
    }

    @Test
    @Issue("JENKINS-44290")
    void shouldInitializeCorrectlyWithDefaults() throws Exception {
        Engine engine = new Engine(null, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        engine.startEngine(true);

        // Cache will go to ~/.jenkins , we do not want to worry about this repo
        assertTrue(
                JarCache.DEFAULT_NOWS_JAR_CACHE_LOCATION.exists(),
                "Default JarCache should be touched: " + JarCache.DEFAULT_NOWS_JAR_CACHE_LOCATION.getAbsolutePath());
    }

    @Test
    void shouldInitializeCorrectlyWithCustomCache() throws Exception {
        File jarCache = new File(tmpDir, "jarCache");

        Engine engine = new Engine(null, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        engine.setJarCache(new FileSystemJarCache(jarCache, true));
        engine.startEngine(true);

        // Cache will go to ~/.jenkins , should be touched by default
        assertTrue(jarCache.exists(), "The specified JarCache should be touched: " + jarCache.getAbsolutePath());
    }

    @Test
    void shouldInitializeCorrectlyWithWorkDir() throws Exception {
        File workDir = new File(tmpDir, "workDir");
        Engine engine = new Engine(null, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        engine.setWorkDir(workDir.toPath());
        engine.startEngine(true);

        WorkDirManager mgr = WorkDirManager.getInstance();
        File workDirLoc = mgr.getLocation(WorkDirManager.DirType.WORK_DIR);
        assertThat(
                "The initialized work directory should equal to the one passed in parameters",
                workDirLoc,
                equalTo(workDir));
        assertTrue(workDir.exists(), "The work directory should exist");
    }

    @Test
    void shouldUseCustomCacheDirIfRequired() throws Exception {
        File workDir = new File(tmpDir, "workDir");
        File jarCache = new File(tmpDir, "jarCache");
        Engine engine = new Engine(null, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        engine.setWorkDir(workDir.toPath());
        engine.setJarCache(new FileSystemJarCache(jarCache, true));
        engine.startEngine(true);

        WorkDirManager mgr = WorkDirManager.getInstance();
        File location = mgr.getLocation(WorkDirManager.DirType.JAR_CACHE_DIR);
        assertThat("WorkDir manager should not be aware about external JAR cache location", location, nullValue());
    }

    @Test
    @Issue("JENKINS-60926")
    void getAgentName() {
        Engine engine = new Engine(null, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        assertThat(engine.getAgentName(), is(AGENT_NAME));
    }

    @Test
    @Timeout(value = 5_000, unit = TimeUnit.MILLISECONDS)
    void shouldNotReconnect() {
        EngineListener l = new EngineListener() {
            @Override
            public void error(Throwable t) {
                throw new NoReconnectException();
            }
        };
        Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        engine.setNoReconnect(true);
        assertThrows(NoReconnectException.class, engine::run);
    }

    private static class NoReconnectException extends RuntimeException {}

    @Test
    @Timeout(value = 30_000, unit = TimeUnit.MILLISECONDS)
    void shouldReconnectOnJnlpAgentEndpointResolutionExceptions() {
        EngineListener l = new EngineListener() {
            private int count;

            @Override
            public void status(String msg) {
                status(msg, null);
            }

            @Override
            public void status(String msg, Throwable t) {
                LOGGER.log(Level.INFO, msg, t);
                if (msg.startsWith("Could not locate server among")) {
                    count++;
                }
                if (count == 2) {
                    throw new ExpectedException();
                }
            }

            @Override
            public void error(Throwable t) {
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                }
            }
        };
        Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        assertThrows(ExpectedException.class, engine::run, "Should have tried at least twice");
    }

    private static class ExpectedException extends RuntimeException {}
}
