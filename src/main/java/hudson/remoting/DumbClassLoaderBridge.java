package hudson.remoting;

import hudson.remoting.RemoteClassLoader.ClassFile;
import hudson.remoting.RemoteClassLoader.ClassFile2;
import hudson.remoting.RemoteClassLoader.IClassLoader;
import hudson.remoting.RemoteClassLoader.ResourceFile;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import javax.annotation.CheckForNull;

/**
 * Implements full {@link IClassLoader} from a legacy one
 * that doesn't support prefetching methods.
 *
 * <p>
 * This simplifies {@link RemoteClassLoader} a little bit as it can now assume the other side
 * supports everything.
 *
 * @author Kohsuke Kawaguchi
 * @see Capability#supportsPrefetch()
 */
class DumbClassLoaderBridge implements IClassLoader {
    private final IClassLoader base;

    DumbClassLoaderBridge(IClassLoader base) {
        this.base = base;
    }

    public byte[] fetchJar(URL url) throws IOException {
        return base.fetchJar(url);
    }

    public byte[] fetch(String className) throws ClassNotFoundException {
        return base.fetch(className);
    }

    public ClassFile fetch2(String className) throws ClassNotFoundException {
        return base.fetch2(className);
    }

    @Override
    public byte[] getResource(String name) throws IOException {
        return base.getResource(name);
    }

    @Override
    public byte[][] getResources(String name) throws IOException {
        return base.getResources(name);
    }

    public Map<String,ClassFile2> fetch3(String className) throws ClassNotFoundException {
        ClassFile cf = fetch2(className);
        return Collections.singletonMap(className,
                new ClassFile2(cf.classLoader,new ResourceImageDirect(cf.classImage),null,null,null));
    }

    @Override
    public ResourceFile getResource2(String name) throws IOException {
        byte[] img = base.getResource(name);
        if (img==null)  return null;
        return new ResourceFile(new ResourceImageDirect(img), null); // we are on the receiving side, so null is ok
    }

    @Override
    public ResourceFile[] getResources2(String name) throws IOException {
        byte[][] r = base.getResources(name);
        ResourceFile[] res = new ResourceFile[r.length];
        for (int i = 0; i < res.length; i++) {
            res[i] = new ResourceFile(new ResourceImageDirect(r[i]),null); // we are on the receiving side, so null is ok
        }
        return res;
    }
}
