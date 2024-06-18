package hudson.remoting;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

/**
 * Remoting-aware holder of {@link ClassLoader} that replaces ClassLoader by its {@link RemoteClassLoader}.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.12
 */
public class ClassLoaderHolder implements SerializableOnlyOverRemoting {

    @CheckForNull
    private transient ClassLoader classLoader;

    public ClassLoaderHolder(@CheckForNull ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public ClassLoaderHolder() {}

    @CheckForNull
    public ClassLoader get() {
        return classLoader;
    }

    public void set(@CheckForNull ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
        RemoteClassLoader.IClassLoader proxy = (RemoteClassLoader.IClassLoader) ois.readObject();
        classLoader = proxy == null
                ? null
                : getChannelForSerialization().importedClassLoaders.get(proxy);
    }

    @SuppressFBWarnings(
            value = "DMI_NONSERIALIZABLE_OBJECT_WRITTEN",
            justification = "RemoteClassLoader.export() produces a serializable wrapper class")
    private void writeObject(ObjectOutputStream oos) throws IOException {
        if (classLoader == null) {
            oos.writeObject(null);
        } else {
            RemoteClassLoader.IClassLoader proxy = RemoteClassLoader.export(classLoader, getChannelForSerialization());
            oos.writeObject(proxy);
        }
    }

    private static final long serialVersionUID = 1L;
}
