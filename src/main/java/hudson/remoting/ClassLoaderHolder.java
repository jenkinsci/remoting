package hudson.remoting;

import hudson.remoting.RemoteClassLoader.IClassLoader;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * Remoting-aware holder of {@link ClassLoader} that replaces ClassLoader by its {@link RemoteClassLoader}.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.12
 */
public class ClassLoaderHolder implements Serializable {
    private transient ClassLoader classLoader;

    public ClassLoaderHolder(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public ClassLoaderHolder() {
    }

    public ClassLoader get() {
        return classLoader;
    }

    public void set(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        IClassLoader proxy = (IClassLoader)ois.readObject();
        classLoader = proxy==null ? null : Channel.current().importedClassLoaders.get(proxy);
    }

    private void writeObject(ObjectOutputStream oos) throws IOException {
        if (classLoader==null)
            oos.writeObject(null);
        else {
            IClassLoader proxy = RemoteClassLoader.export(classLoader, Channel.current());
            oos.writeObject(proxy);
        }
    }

    private static final long serialVersionUID = 1L;
}
