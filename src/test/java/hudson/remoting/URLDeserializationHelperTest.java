package hudson.remoting;

import java.io.IOException;
import java.net.Proxy;
import java.net.URL;
import org.junit.jupiter.api.Test;

class URLDeserializationHelperTest {

    @Test
    void openURLWithProxy() throws IOException {
        URL original = new URL("https://localhost");
        URL url = URLDeserializationHelper.wrapIfRequired(original);
        url.openConnection(Proxy.NO_PROXY);
    }
}
