package hudson.remoting;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

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

    /**
     * Converts URLish to the standard {@link URL} type.
     * @return URL or {@code null} if the target destination is known to be non-existent.
     * @throws MalformedURLException URL cannot be constructed
     */
    @CheckForNull
    abstract URL toURL() throws MalformedURLException;

    @Nonnull
    static URLish from(@Nonnull final URL url) {
        
        return new URLish() {
            @Override
            @Nonnull
            URL toURL() {
                return url;
            }
        };
    }

    @Nonnull
    static URLish from(@Nonnull final File f) {
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
