package hudson.remoting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A JAR cache stub, which actually does not implement any caching.
 * This cache is intended to be used with the -jar-cache-disabled option.
 * @author Oleg Nenashev, Synopsys Inc.
 * @since TODO
 */
public class StubJarCache extends JarCacheSupport {
   
    public StubJarCache() {
    }

    @Override
    protected final URL lookInCache(Channel channel, long sum1, long sum2) throws IOException, InterruptedException {
        return null;
    }

    @Override
    protected URL retrieve(Channel channel, long sum1, long sum2) throws IOException, InterruptedException {
        final String target = map(sum1, sum2);
        try {
            File tmp = File.createTempFile(target,"tmp");
            try {
                RemoteOutputStream o = new RemoteOutputStream(new FileOutputStream(tmp));
                try {
                    LOGGER.log(Level.FINE, String.format("Retrieving jar file %16X%16X",sum1,sum2));
                    getJarLoader(channel).writeJarTo(sum1, sum2, o);
                } finally {
                    o.close();
                }

                return tmp.toURI().toURL();             
            } finally {
                tmp.delete();
            }
        } catch (IOException e) {
            throw (IOException)new IOException("Failed to write to "+target).initCause(e);
        }
    }

    /**
     * Map to the cache jar file name.
     */
    String map(long sum1, long sum2) {
        return String.format("jar_%02X/%014X%016X.jar",
                (int)(sum1>>>(64-8)),
                sum1&0x00FFFFFFFFFFFFFFL, sum2);
    }

    private static final Logger LOGGER = Logger.getLogger(FileSystemJarCache.class.getName());
}
