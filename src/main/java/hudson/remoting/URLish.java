package hudson.remoting;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Something that's effectively URL.
 *
 * <p>
 * Thie indirection on {@link URL} allows us to make sure that URLs backed by temporary files
 * actually do exist before we use them.
 *
 * @author Kohsuke Kawaguchi
 */
abstract class URLish {
    private URLish() {}

    abstract URL toURL() throws MalformedURLException;

    static URLish from(final URL url) {
        if (url==null)  return null;
        return new URLish() {
            @Override
            URL toURL() {
                return url;
            }
        };
    }

    static URLish from(final File f) {
        if (f==null)  return null;
        return new URLish() {
            @Override
            URL toURL() throws MalformedURLException {
                // be defensive against external factors that might have deleted this file, since we use /tmp
                // see http://www.nabble.com/Surefire-reports-tt17554215.html
                if (f.exists())
                    return f.toURI().toURL();
                return null;
            }
        };
    }
}
