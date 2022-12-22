package hudson.remoting;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.concurrent.ExecutionException;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.AllOf.allOf;
import static org.hamcrest.core.StringContains.containsString;
import static org.hamcrest.core.StringEndsWith.endsWith;
import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class JarLoaderImplTest implements Serializable {
    private transient URLClassLoader cl;
    private File dir;

    // checksum of the jar files to force loading
    private Checksum sum1, sum2;

    private URL jar1 = null;
    private URL jar2 = null;

    void withChannel(ChannelRunner channelRunner, ChannelRunner.ConsumerThrowable<Channel, Exception> f) throws Exception {
        channelRunner.withChannel(((ChannelRunner.ConsumerThrowable<Channel, Exception>) this::setUp).andThen(f));
    }



    protected void setUp(Channel channel) throws Exception {
        jar1 = getClass().getClassLoader().getResource("remoting-test-client.jar");
        jar2 = getClass().getClassLoader().getResource("remoting-test-client-tests.jar");

        cl = new URLClassLoader(new URL[]{toFile(jar1).toURI().toURL(), toFile(jar2).toURI().toURL()}, this.getClass().getClassLoader());

        channel.setJarCache(new FileSystemJarCache(dir, true));
        channel.call(new JarCacherCallable());
        sum1 = channel.jarLoader.calcChecksum(jar1);
        sum2 = channel.jarLoader.calcChecksum(jar2);
    }

    private File toFile(URL url) {
        try {
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            return new File(url.getPath());
        }
    }

    @AfterEach
    protected void tearDown() throws Exception {
        if (cl != null) {
            cl.close();
            cl = null;
        }

        if (Launcher.isWindows()) {
            // Current Resource loader implementation keep files open even if we close the classloader.
            // This check has been never working correctly in Windows.
            // TODO: Fix it as a part of JENKINS-38696
            return;
        }

        // because the dir is used by FIleSystemJarCache to asynchronously load stuff
        // we might fail to shut it down right away
        if (dir != null) {
            for (int i = 0; ; i++) {
                try {
                    FileUtils.deleteDirectory(dir);
                    return;
                } catch (IOException e) {
                    if (i == 3) {
                        throw e;
                    }
                    Thread.sleep(1000);
                }
            }
        }
    }

    /**
     * This should cause the jar file to be sent to the other side
     */
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testJarLoadingTest(ChannelRunner channelRunner) throws Exception {
        assumeFalse(channelRunner instanceof InProcessCompatibilityRunner);

        dir = Files.createTempDirectory("remoting-cache").toFile();

        withChannel(channelRunner, channel -> {
            sum1 = channel.jarLoader.calcChecksum(jar1);
            sum2 = channel.jarLoader.calcChecksum(jar2);
            System.out.println("Channel "+ channel.getName());
            JarLoaderCache.showInfo();
        });

        withChannel(channelRunner, channel -> {
//            sum1 = channel.jarLoader.calcChecksum(jar1);
//            sum2 = channel.jarLoader.calcChecksum(jar2);
            System.out.println("Channel "+ channel.getName());
            JarLoaderCache.showInfo();
        });
    }



    private void verifyResource(String v) {
        assertThat(v, allOf(startsWith("jar:file:"), containsString(dir.toURI().getPath()), endsWith("::hello")));
    }


    /**
     * Once the jar files are cached, ClassLoader.getResources() should return jar URLs.
     */
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testGetResources(ChannelRunner channelRunner) throws Exception {
        assumeFalse(channelRunner instanceof InProcessCompatibilityRunner);
        withChannel(channelRunner, channel -> {
            channel.call(new ForceJarLoad(sum1));
            channel.call(new ForceJarLoad(sum2));

            Callable<String, IOException> c = (Callable<String, IOException>) cl.loadClass("test.HelloGetResources").getDeclaredConstructor().newInstance();
            String v = channel.call(c);
            System.out.println(v);  // should find two resources

            String[] lines = v.split("\n");

            verifyResource(lines[0]);

            assertThat(lines[1], allOf(startsWith("jar:file:"), containsString(dir.toURI().getPath()), endsWith("::hello2")));
        });
    }




    /**
     * Force the remote side to fetch the retrieval of the specific jar file.
     */
    private static final class ForceJarLoad extends CallableBase<Void, IOException> implements Serializable {
        private final long sum1, sum2;

        private ForceJarLoad(Checksum sum) {
            this.sum1 = sum.sum1;
            this.sum2 = sum.sum2;
        }

        @Override
        public Void call() throws IOException {
            try {
                final Channel ch = Channel.currentOrFail();
                final JarCache jarCache = ch.getJarCache();
                if (jarCache == null) {
                    throw new IOException("Cannot Force JAR load, JAR cache is disabled");
                }
                jarCache.resolve(ch, sum1, sum2).get();
                return null;
            } catch (InterruptedException | ExecutionException e) {
                throw new IOException(e);
            }
        }

        private static final long serialVersionUID = 1L;
    }

    private class JarCacherCallable extends CallableBase<Void, IOException> {
        @Override
        public Void call() {
            Channel.currentOrFail().setJarCache(new FileSystemJarCache(dir, true));
            return null;
        }

        private static final long serialVersionUID = 1L;
    }

    private static final long serialVersionUID = 1L;
}


