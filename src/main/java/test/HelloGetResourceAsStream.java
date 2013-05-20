package test;

import hudson.remoting.Callable;
import org.apache.commons.io.IOUtils;

import java.io.IOException;

/**
 * @author Kohsuke Kawaguchi
 */
public class HelloGetResourceAsStream implements Callable<String,IOException> {
    public String call() throws IOException {
        return IOUtils.toString(getClass().getResourceAsStream("hello.txt"));
    }
}
