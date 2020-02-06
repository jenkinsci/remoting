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

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import org.jenkinsci.remoting.engine.WorkDirManager;
import org.jenkinsci.remoting.engine.WorkDirManagerRule;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;

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
        jenkinsUrls = Arrays.asList(new URL("http://my.jenkins.not.existent"));
    }
    
    @Test
    @Issue("JENKINS-44290")
    public void shouldInitializeCorrectlyWithDefaults() throws Exception {
        EngineListener l = new TestEngineListener();
        Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        engine.startEngine(true);
        
        // Cache will go to ~/.jenkins , we do not want to worry anbout this repo
        Assert.assertTrue("Default JarCache should be touched: " + JarCache.DEFAULT_NOWS_JAR_CACHE_LOCATION.getAbsolutePath(), 
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
        Assert.assertTrue("The specified JarCache should be touched: " + jarCache.getAbsolutePath(), 
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
        Assert.assertThat("The initialized work directory should equal to the one passed in parameters", 
                workDirLoc, equalTo(workDir));
        Assert.assertTrue("The work directory should exist", workDir.exists());
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
        Assert.assertThat("WorkDir manager should not be aware about external JAR cache location", location, nullValue());
    }

    @Test
    @Issue("JENKINS-60926")
    public void getAgentName() {
        EngineListener l = new TestEngineListener();
        Engine engine = new Engine(l, jenkinsUrls, SECRET_KEY, AGENT_NAME);
        assertThat(engine.getAgentName(), is(AGENT_NAME));
    }

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
