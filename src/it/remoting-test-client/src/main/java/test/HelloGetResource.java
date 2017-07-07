package test;

import hudson.remoting.Callable;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.URL;

/**
 * @author Kohsuke Kawaguchi
 */
public class HelloGetResource implements Callable<String,IOException> {
    public String call() throws IOException {
        URL u = getClass().getResource("hello.txt");
        return u+"::"+IOUtils.toString(u);
    }
}
