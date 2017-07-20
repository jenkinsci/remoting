package test;

import hudson.remoting.Callable;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;

/**
 * Lists all resources as a single string.
 * @author Kohsuke Kawaguchi
 */
public class HelloGetResources implements Callable<String,IOException> {

    final boolean useCaches;
    
    @Deprecated
    public HelloGetResources() {
        this(true);
    }

    /**
     * Constructor.
     * 
     * @param useCaches If {@code false}, caching in the {@link URLConnection} will be disabled.
     *                  A default value will be used otherwise.
     * @since 1.1
     */
    public HelloGetResources(boolean useCaches) {
        this.useCaches = useCaches;
    }
    
    @Override
    public String call() throws IOException {
        Enumeration<URL> e = getClass().getClassLoader().getResources("test/hello.txt");
        StringBuilder b = new StringBuilder();
        while (e.hasMoreElements()) {
            URL u = e.nextElement();
            URLConnection connection = u.openConnection();
            if (!useCaches) {
                connection.setUseCaches(false);
            }
            
            b.append(u);
            b.append("::");
            try (InputStream istream = connection.getInputStream()) {
                b.append(IOUtils.toString(istream));
            }
            b.append('\n');
        }
        return b.toString();
    }
}
