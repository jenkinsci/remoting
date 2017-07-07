    package hudson.remoting;

import com.google.common.base.Function;
import com.google.common.base.Predicate;

import org.apache.commons.io.FileUtils;
import org.apache.tools.ant.AntClassLoader;
import org.junit.Assert;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.core.AllOf.allOf;
import static org.hamcrest.core.StringContains.containsString;
import static org.hamcrest.core.StringEndsWith.endsWith;
import static org.hamcrest.core.StringStartsWith.startsWith;

/**
 * @author Kohsuke Kawaguchi
 */
public class PrefetchingTest extends RmiTestBase implements Serializable {
    private transient AntClassLoader cl;
    private File dir;

    // checksum of the jar files to force loading
    private Checksum sum1,sum2;
    
    // TODO: Rework to Rule once RmiTestBase is updated to JUnit 4/5
    private boolean oldCachingValue;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        oldCachingValue = ResourceImageInJar.DISABLE_FILE_CACHING_IN_JAR_CONNECTION;
        if (Launcher.isWindows()) {
            // On Windows the test relies on the file locks cleanup when removing the temporary directory
            ResourceImageInJar.DISABLE_FILE_CACHING_IN_JAR_CONNECTION = true;
        }
        
        URL jar1 = getClass().getClassLoader().getResource("remoting-test-client.jar");
        URL jar2 = getClass().getClassLoader().getResource("remoting-test-client-tests.jar");

        cl = new AntClassLoader(this.getClass().getClassLoader(),true);
        cl.addPathComponent(toFile(jar1));
        cl.addPathComponent(toFile(jar2));

        dir = File.createTempFile("remoting", "cache");
        dir.delete();
        dir.mkdirs();

        channel.setJarCache(new FileSystemJarCache(dir, true));
        channel.call(new CallableBase<Void, IOException>() {
            public Void call() throws IOException {
                Channel.current().setJarCache(new FileSystemJarCache(dir, true));
                return null;
            }
        });
        sum1 = channel.jarLoader.calcChecksum(jar1);
        sum2 = channel.jarLoader.calcChecksum(jar2);
    }

    private File toFile(URL url) {
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            return new File(url.getPath());
        }
    }

    @Override
    protected void tearDown() throws Exception {
        ResourceImageInJar.DISABLE_FILE_CACHING_IN_JAR_CONNECTION = oldCachingValue;
        cl.cleanup();
        super.tearDown();
        
        // Cleanup the temporary cache and ensure we have no locked files left
        FileUtils.deleteDirectory(dir);
    }

    /**
     * This should cause the jar file to be sent to the other side
     */
    public void testJarLoadingTest() throws Exception {
        channel.call(new ForceJarLoad(sum1));
        channel.call(new ForceJarLoad(sum2));

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
        channel.call(new ForceJarLoad(sum1));
        channel.call(new ForceJarLoad(sum2));

        Callable<String,IOException> c = (Callable<String,IOException>)cl.loadClass("test.HelloGetResource").newInstance();
        String v = channel.call(c);
        System.out.println(v);

        verifyResource(v);
    }

    public void testGetResource_precache() throws Exception {
        Callable<String,IOException> c = (Callable<String,IOException>)cl.loadClass("test.HelloGetResource").newInstance();
        String v = channel.call(c);
        System.out.println(v);

        verifyResourcePrecache(v);
    }

    public void testGetResourceAsStream() throws Exception {
        Callable<String,IOException> c = (Callable<String,IOException>)cl.loadClass("test.HelloGetResourceAsStream").newInstance();
        String v = channel.call(c);
        assertEquals("hello",v);
    }

    /**
     * Validates that the resource is coming from a jar.
     */
    private void verifyResource(String v) throws IOException, InterruptedException {
        Assert.assertThat(v, allOf(startsWith("jar:file:"), 
                                   containsString(dir.toURI().getPath()), 
                                   endsWith("::hello")));
    }

    /**
     * Validates that the resource is coming from a file path.
     */
    private void verifyResourcePrecache(String v) throws IOException, InterruptedException {
        assertTrue(v, v.startsWith("file:"));
        assertTrue(v, v.endsWith("::hello"));
    }

    /**
     * Once the jar files are cached, ClassLoader.getResources() should return jar URLs.
     */
    public void testGetResources() throws Exception {
        channel.call(new ForceJarLoad(sum1));
        channel.call(new ForceJarLoad(sum2));

        Callable<String,IOException> c = (Callable<String,IOException>)cl.loadClass("test.HelloGetResources").newInstance();
        String v = channel.call(c);
        System.out.println(v);  // should find two resources

        String[] lines = v.split("\n");

        verifyResource(lines[0]);

        Assert.assertThat(lines[1], allOf(startsWith("jar:file:"), 
                                          containsString(dir.toURI().getPath()), 
                                          endsWith("::hello2")));
    }

    /**
     * Unlike {@link #testGetResources()}, the URL should begin with file:... before the jar file gets cached
     */
    public void testGetResources_precache() throws Exception {
        Callable<String,IOException> c = (Callable<String,IOException>)cl.loadClass("test.HelloGetResources").newInstance();
        String v = channel.call(c);
        System.out.println(v);  // should find two resources

        String[] lines = v.split("\n");

        assertTrue(lines[0], lines[0].startsWith("file:"));
        assertTrue(lines[1], lines[1].startsWith("file:"));
        assertTrue(lines[0], lines[0].endsWith("::hello"));
        assertTrue(lines[1], lines[1].endsWith("::hello2"));
    }

    public void testInnerClass() throws Exception {
        Echo<Object> e = new Echo<Object>();
        e.value = cl.loadClass("test.Foo").newInstance();
        Object r = channel.call(e);

        ((Predicate)r).apply(null); // this verifies that the object is still in a good state
    }

    private static final class Echo<V> extends CallableBase<V,IOException> implements Serializable {
        V value;

        public V call() throws IOException {
            return value;
        }
    }

    /**
     * Force the remote side to fetch the retrieval of the specific jar file.
     */
    private static final class ForceJarLoad extends CallableBase<Void,IOException> implements Serializable{
        private final long sum1,sum2;

        private ForceJarLoad(Checksum sum) {
            this.sum1 = sum.sum1;
            this.sum2 = sum.sum2;
        }

        public Void call() throws IOException {
            try {
                Channel ch = Channel.current();
                ch.getJarCache().resolve(ch,sum1,sum2).get();
                return null;
            } catch (InterruptedException e) {
                throw new IOException(e);
            } catch (ExecutionException e) {
                throw new IOException(e);
            }
        }
    }
}
