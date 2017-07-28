package test;

import hudson.remoting.Callable;
import org.apache.commons.io.IOUtils;

import java.io.IOException;

/**
 * Gets a single resource as a stream.
 * This call uses {@link Class#getResourceAsStream(java.lang.String)} instead 
 * of access by URL like {@link HelloGetResource} does.
 * 
 * @author Kohsuke Kawaguchi
 * @see HelloGetResource
 */
public class HelloGetResourceAsStream implements Callable<String,IOException> {
    
    // TODO: So, how do we protect it from the resource leak in Windows?
    // Perhaps ResourceImageBoth patch in the Remoting library is enough
    @Override
    public String call() throws IOException {
        return IOUtils.toString(getClass().getResourceAsStream("hello.txt"));
    }
}
