package hudson.remoting;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
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

    /**
     * Converts URLish to the standard {@link URL} type.
     * @return URL or {@code null} if the target destination is known to be non-existent.
     * @throws MalformedURLException URL cannot be constructed
     */
    @CheckForNull
    abstract URL toURL() throws MalformedURLException;

    @NonNull
    static URLish from(@NonNull final URL url) {

        return new URLish() {
            @Override
            @NonNull
            URL toURL() {
                return url;
            }
        };
    }

    @NonNull
    static URLish from(@NonNull final File f) {
        return new URLish() {
            @Override
            URL toURL() throws MalformedURLException {
                // be defensive against external factors that might have deleted this file, since we use /tmp
                // see http://www.nabble.com/Surefire-reports-tt17554215.html
                if (f.exists()) {
                    return f.toURI().toURL();
                }
                return null;
            }
        };
    }
}
