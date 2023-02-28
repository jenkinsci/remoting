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

import org.jenkinsci.remoting.engine.WorkDirManager;
import org.jenkinsci.remoting.engine.WorkDirManagerRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;

import java.io.File;
import java.net.URL;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Tests of {@link Engine}
 * @author Oleg Nenashev
 */
public class EngineTest {
    
    private static final String SECRET_KEY = "Hello, world!";
    private static final String AGENT_NAME = "testAgent";
    private List<URL> jenkinsUrls;
    
    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();
    
    @Rule
    public WorkDirManagerRule mgr = new WorkDirManagerRule();
    
    @Before
    public void init() throws Exception {
        jenkinsUrls = List.of(new URL("http://my.jenkins.not.existent"));
    }
    
    @Test
    @Issue("JENKINS-44290")
    public void shouldInitializeCorrectlyWithDefaults() throws Exception {
        EngineListener l = new TestEngineListener();
        Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        engine.startEngine(true);
        
        // Cache will go to ~/.jenkins , we do not want to worry anbout this repo
        assertTrue("Default JarCache should be touched: " + JarCache.DEFAULT_NOWS_JAR_CACHE_LOCATION.getAbsolutePath(), 
                JarCache.DEFAULT_NOWS_JAR_CACHE_LOCATION.exists());
    }
    
    @Test
    public void shouldInitializeCorrectlyWithCustomCache() throws Exception {
        File jarCache = new File(tmpDir.getRoot(), "jarCache");
        
        EngineListener l = new TestEngineListener();
        Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        engine.setJarCache(new FileSystemJarCache(jarCache, true));
        engine.startEngine(true);
        
        // Cache will go to ~/.jenkins , should be touched by default
        assertTrue("The specified JarCache should be touched: " + jarCache.getAbsolutePath(), 
                jarCache.exists());
    }
    
    @Test
    public void shouldInitializeCorrectlyWithWorkDir() throws Exception {
        File workDir = new File(tmpDir.getRoot(), "workDir");
        EngineListener l = new TestEngineListener();
        Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        engine.setWorkDir(workDir.toPath());
        engine.startEngine(true);
        
        WorkDirManager mgr = WorkDirManager.getInstance();
        File workDirLoc = mgr.getLocation(WorkDirManager.DirType.WORK_DIR);
        assertThat("The initialized work directory should equal to the one passed in parameters", 
                workDirLoc, equalTo(workDir));
        assertTrue("The work directory should exist", workDir.exists());
    }
    
    @Test
    public void shouldUseCustomCacheDirIfRequired() throws Exception {
        File workDir = new File(tmpDir.getRoot(), "workDir");
        File jarCache = new File(tmpDir.getRoot(), "jarCache");
        EngineListener l = new TestEngineListener();
        Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        engine.setWorkDir(workDir.toPath());
        engine.setJarCache(new FileSystemJarCache(jarCache, true));
        engine.startEngine(true);
        
        WorkDirManager mgr = WorkDirManager.getInstance();
        File location = mgr.getLocation(WorkDirManager.DirType.JAR_CACHE_DIR);
        assertThat("WorkDir manager should not be aware about external JAR cache location", location, nullValue());
    }

    @Test
    @Issue("JENKINS-60926")
    public void getAgentName() {
        EngineListener l = new TestEngineListener();
        Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        assertThat(engine.getAgentName(), is(AGENT_NAME));
    }

    @Test
    public void shouldNotReconnect() {
        EngineListener l = new TestEngineListener() {
            @Override
            public void error(Throwable t) {
                throw new NoReconnectException();
            }
        };
        Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        assertThrows(NoReconnectException.class, () -> engine.run());
    }

    private static class NoReconnectException extends RuntimeException {}

    @Test
    public void shouldNotReconnectOnJnlpAgentEndpointResolutionExceptionsButWithStatus() {
        EngineListener l = new TestEngineListener() {
            @Override
            public void status(String msg, Throwable t) {
                System.err.println("Status: " + msg);
                if (msg.startsWith("Could not resolve JNLP agent endpoint")) {
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
        Engine.nonFatalJnlpAgentEndpointResolutionExceptions = true;
        try {
            Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
            assertThrows("Message should have started with 'Could not resolve...'", ExpectedException.class, () -> engine.run());
        } finally {
            // reinstate the static value
            Engine.nonFatalJnlpAgentEndpointResolutionExceptions = false;
        }
    }

    @Test
    public void shouldReconnectOnJnlpAgentEndpointResolutionExceptionsMaxRetries() {
        EngineListener l = new TestEngineListener() {
            private int count;

            @Override
            public void status(String msg, Throwable t) {
                System.err.println("Status: " + msg);
                if (msg.startsWith("Could not resolve JNLP agent endpoint")) {
                    count++;
                }
                if (count == 5) {
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

        int currentMaxRetries = Engine.nonFatalJnlpAgentEndpointResolutionExceptionsMaxRetries;
        int currentIntervalInSeconds = Engine.nonFatalJnlpAgentEndpointResolutionExceptionsIntervalInMillis;
        try {
            Engine.nonFatalJnlpAgentEndpointResolutionExceptions = true;
            Engine.nonFatalJnlpAgentEndpointResolutionExceptionsMaxRetries = 5;
            Engine.nonFatalJnlpAgentEndpointResolutionExceptionsIntervalInMillis = 100;
            Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
            assertThrows("Should have tried at least five times", ExpectedException.class, () -> engine.run());
        } finally {
            // reinstate the static values
            Engine.nonFatalJnlpAgentEndpointResolutionExceptions = false;
            Engine.nonFatalJnlpAgentEndpointResolutionExceptionsMaxRetries = currentMaxRetries;
            Engine.nonFatalJnlpAgentEndpointResolutionExceptionsIntervalInMillis = currentIntervalInSeconds;
        }
    }

    private static class ExpectedException extends RuntimeException {}

    private static class TestEngineListener implements EngineListener {

        @Override
        public void status(String msg) {
            // Do nothing
        }

        @Override
        public void status(String msg, Throwable t) {
            // Do nothing
        }

        @Override
        public void error(Throwable t) {
            // Do nothing
        }

        @Override
        public void onDisconnect() {
            // Do nothing
        }

        @Override
        public void onReconnect() {
            // Do nothing
        }
        
    }
}
