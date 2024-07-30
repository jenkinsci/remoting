package hudson.remoting;

import java.io.IOException;
import java.net.URL;

/**
 * Validate a URL attempted to be read by the remote end (agent side).
 *
 * @deprecated Do not use, intended as a temporary workaround only.
 */
// TODO Remove once we no longer require compatibility with remoting before 2024-08.
@Deprecated
public interface JarURLValidator {
    void validate(URL url) throws IOException;
}
