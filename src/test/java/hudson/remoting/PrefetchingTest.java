package hudson.remoting;

import com.google.common.base.Function;
import org.apache.commons.io.FileUtils;
import sun.tools.jar.resources.jar;

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
        URL jar1 = getClass().getClassLoader().getResource("remoting-test-client.jar");
        URL jar2 = getClass().getClassLoader().getResource("remoting-test-client-tests.jar");
        cl = new URLClassLoader(new URL[]{jar1,jar2}, this.getClass().getClassLoader());

        dir = File.createTempFile("remoting", "cache");
        dir.delete();
        dir.mkdirs();

        channel.setJarCache(new FileSystemJarCache(dir, true));
        channel.call(new Callable<Void, IOException>() {
            public Void call() throws IOException {
                Channel.current().setJarCache(new FileSystemJarCache(dir, true));
                return null;
            }
        });
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        FileUtils.deleteDirectory(dir);
    }

    /**
     * This should cause the jar file to be sent to the other side
1     */
    public void testJarLoadingTest() throws Exception {
        Callable<Void,IOException> sc = (Callable)cl.loadClass("test.ClassLoadingFromJarTester").newInstance();
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

    public void testGetResource() throws Exception {
        Callable<String,IOException> c = (Callable<String,IOException>)cl.loadClass("test.HelloGetResource").newInstance();
        String v = channel.call(c);
        System.out.println(v);

        verifyResource(v);
    }

    public void testGetResourceAsStream() throws Exception {
        Callable<String,IOException> c = (Callable<String,IOException>)cl.loadClass("test.HelloGetResourceAsStream").newInstance();
        String v = channel.call(c);
        assertEquals("hello",v);
    }

    private void verifyResource(String v) throws IOException, InterruptedException {
        assertTrue(v.startsWith("jar:file:"));
        assertTrue(v.contains(dir.getPath()));
        assertTrue(v.endsWith("::hello"));
    }

    public void testGetResources() throws Exception {
        Callable<String,IOException> c = (Callable<String,IOException>)cl.loadClass("test.HelloGetResources").newInstance();
        String v = channel.call(c);
        System.out.println(v);  // should find two resources

        String[] lines = v.split("\n");

        verifyResource(lines[0]);

        assertTrue(lines[1].startsWith("jar:file:"));
        assertTrue(lines[1].contains(dir.getPath()));
        assertTrue(lines[1].endsWith("::hello2"));
    }
}
