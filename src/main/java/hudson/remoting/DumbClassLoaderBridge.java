package hudson.remoting;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.Map;

/**
 * Implements full {@link RemoteClassLoader.IClassLoader} from a legacy one
 * that doesn't support prefetching methods.
 *
 * <p>
 * This simplifies {@link RemoteClassLoader} a little bit as it can now assume the other side
 * supports everything.
 *
 * @author Kohsuke Kawaguchi
 * @see Capability#supportsPrefetch()
 */
class DumbClassLoaderBridge implements RemoteClassLoader.IClassLoader {
    @NonNull
    private final RemoteClassLoader.IClassLoader base;

    DumbClassLoaderBridge(@NonNull RemoteClassLoader.IClassLoader base) {
        this.base = base;
    }

    @Override
    public byte[] fetch(String className) throws ClassNotFoundException {
        return base.fetch(className);
    }

    @Override
    public RemoteClassLoader.ClassFile fetch2(String className) throws ClassNotFoundException {
        return base.fetch2(className);
    }

    @Override
    public byte[] getResource(String name) throws IOException {
        return base.getResource(name);
    }

    @Override
    @NonNull
    public byte[][] getResources(String name) throws IOException {
        return base.getResources(name);
    }

    @Override
    public Map<String, RemoteClassLoader.ClassFile2> fetch3(String className) throws ClassNotFoundException {
        RemoteClassLoader.ClassFile cf = fetch2(className);
        return Map.of(
                className,
                new RemoteClassLoader.ClassFile2(
                        cf.classLoader, new ResourceImageDirect(cf.classImage), null, null, null));
    }

    @Override
    public RemoteClassLoader.ResourceFile getResource2(String name) throws IOException {
        byte[] img = base.getResource(name);
        if (img == null) {
            return null;
        }
        return new RemoteClassLoader.ResourceFile(
                new ResourceImageDirect(img), null); // we are on the receiving side, so null is ok
    }

    @Override
    @NonNull
    public RemoteClassLoader.ResourceFile[] getResources2(String name) throws IOException {
        byte[][] r = base.getResources(name);
        RemoteClassLoader.ResourceFile[] res = new RemoteClassLoader.ResourceFile[r.length];
        for (int i = 0; i < res.length; i++) {
            res[i] = new RemoteClassLoader.ResourceFile(
                    new ResourceImageDirect(r[i]), null); // we are on the receiving side, so null is ok
        }
        return res;
    }

    @Override
    public String getName() throws IOException {
        return base.getName();
    }
}
