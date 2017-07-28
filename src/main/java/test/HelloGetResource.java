package test;

import hudson.remoting.Callable;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

/**
 * Gets a single resource as a string.
 * This method retrieves resource URL and then uses low-level methods to read it.
 * 
 * @author Kohsuke Kawaguchi
 */
public class HelloGetResource implements Callable<String,IOException> {
    
    final boolean useCaches;

    @Deprecated
    public HelloGetResource() {
        this(true);
    }

    /**
     * Constructor.
     * 
     * @param useCaches If {@code false}, caching in the {@link URLConnection} will be disabled.
     *                  A default value will be used otherwise.
     * @since 1.1
     */
    public HelloGetResource(boolean useCaches) {
        this.useCaches = useCaches;
    }
        
    @Override
    public String call() throws IOException {
        URL u = getClass().getResource("hello.txt");
        
        URLConnection connection = u.openConnection();
        if (!useCaches) {
            connection.setUseCaches(false);
        }
        
        try(InputStream istream = connection.getInputStream()) {
            return u+"::" + IOUtils.toString(istream);
        }
    }
}
