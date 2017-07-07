package test;

import hudson.remoting.Callable;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

/**
 * @author Kohsuke Kawaguchi
 */
public class HelloGetResources implements Callable<String,IOException> {
    public String call() throws IOException {
        Enumeration<URL> e = getClass().getClassLoader().getResources("test/hello.txt");
        StringBuilder b = new StringBuilder();
        while (e.hasMoreElements()) {
            URL u = e.nextElement();
            b.append(u);
            b.append("::");
            b.append(IOUtils.toString(u));
            b.append('\n');
        }
        return b.toString();
    }
}
