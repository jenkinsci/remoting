package hudson.remoting;

import com.google.common.base.Function;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * @author Kohsuke Kawaguchi
 */
public class PrefetchingTest extends RmiTestBase implements Serializable {
    private transient URLClassLoader cl;
    private File dir;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // TODO: update POM to copy this jar into the test-classes before we run a test
        URL jar = getClass().getClassLoader().getResource("remoting-test-client.jar");
        cl = new URLClassLoader(new URL[]{jar});

        dir = File.createTempFile("remoting", "cache");
        dir.delete();
        dir.mkdirs();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        FileUtils.deleteDirectory(dir);
    }

    /**
     * This should cause the jar file to be sent to the other side
     */
    public void testJarLoadingTest() throws Throwable {
        channel.setJarCache(new FileSystemJarCache(dir,true));
        channel.call(new Callable<Object, Throwable>() {
            public Object call() throws Throwable {
                Channel.current().setJarCache(new FileSystemJarCache(dir,true));
                return null;
            }
        });

        Callable sc = (Callable)cl.loadClass("test.ClassLoadingFromJarTester").newInstance();
        ((Function)sc).apply(new Verifier());
        assertNull(channel.call(sc));
    }

    private static class Verifier implements Function<Object,Object>, Serializable {
        public Object apply(Object o) {
            try {
                // verify that 'o' is loaded from a jar file
                String loc = Which.classFileUrl(o.getClass()).toExternalForm();
                System.out.println(loc);
                assertTrue(loc, loc.startsWith("jar:"));
                return null;
            } catch (IOException e) {
                throw new Error(e);
            }
        }
    }
}
