package hudson.remoting;

import java.io.IOException;
import java.net.URL;

import static hudson.remoting.Util.*;

/**
 * @author Kohsuke Kawaguchi
 */
class ResourceImageDirect extends ResourceImageRef {
    private final byte[] payload;

    ResourceImageDirect(byte[] payload) {
        this.payload = payload;
    }

    ResourceImageDirect(URL resource) throws IOException {
        this(Util.readFully(resource.openStream()));
    }

    @Override
    byte[] resolve(Channel channel, String resourcePath) throws IOException {
        return payload;
    }

    @Override
    URLish resolveURL(Channel channel, String resourcePath) throws IOException {
        return URLish.from(makeResource(getBaseName(resourcePath), payload));
    }
}
