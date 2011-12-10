package hudson.remoting;

import junit.framework.Test;

import java.io.Serializable;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * @author Kohsuke Kawaguchi
 */
public class RemoteClassLoaderHookTest extends RmiTestBase implements Serializable {
    public void testLoadJar() throws Exception {
        URLClassLoader cl = new URLClassLoader(new URL[]{
                getClass().getClassLoader().getResource("someJar.jar")});
        final ClassLoaderHolder h = new ClassLoaderHolder(cl);

        channel.setRemoteClassLoaderHook(RemoteClassLoaderHook.JAR);
        channel.call(new Callable<Object,Exception>() {
            public Object call() throws Exception {
                RemoteClassLoader ucl = (RemoteClassLoader)h.get();

                assertFalse("someJar.jar shouldn't be in the load list", findSomeJar(ucl));
                Class<?> c = h.get().loadClass("ClassInSomeJar");
                System.out.println(Which.jarFile(c));
                assertTrue("Didn't fetch someJar.jar", findSomeJar(ucl));
                return null;
            }

            private boolean findSomeJar(RemoteClassLoader ucl) {
                for (URL url : ucl.getURLs()) {
                    if (url.toExternalForm().endsWith("someJar.jar"))
                        return true;
                }
                return false;
            }
        });
    }

    public static Test suite() throws Exception {
        return buildSuite(RemoteClassLoaderHookTest.class);
    }

    private Object writeReplace() {
        return null;
    }
}
