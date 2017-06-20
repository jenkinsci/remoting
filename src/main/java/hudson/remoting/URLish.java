package hudson.remoting;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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

    @CheckForNull
    abstract URL toURL() throws MalformedURLException;

    @Nullable
    static URLish from(@CheckForNull final URL url) {
        if (url==null)  return null;
        
        return new URLish() {
            @Override
            @Nonnull
            URL toURL() {
                return url;
            }
        };
    }

    @Nullable
    static URLish from(@CheckForNull final File f) {
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
