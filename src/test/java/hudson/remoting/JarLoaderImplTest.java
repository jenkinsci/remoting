package hudson.remoting;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class JarLoaderImplTest implements Serializable {
    private transient URLClassLoader cl;
    private File dir;

    // checksum of the jar files to force loading

    private URL jar1 = null;
    private URL jar2 = null;

    void withChannel(ChannelRunner channelRunner, ChannelRunner.ConsumerThrowable<Channel, Exception> f) throws Exception {
        channelRunner.withChannel(((ChannelRunner.ConsumerThrowable<Channel, Exception>) this::setUp).andThen(f));
    }



    protected void setUp(Channel channel) throws Exception {
        jar1 = getClass().getClassLoader().getResource("remoting-test-client.jar");
        jar2 = getClass().getClassLoader().getResource("remoting-test-client-tests.jar");

        cl = new URLClassLoader(new URL[]{toFile(jar1).toURI().toURL(), toFile(jar2).toURI().toURL()}, this.getClass().getClassLoader());

        dir = Files.createTempDirectory("remoting-cache").toFile();

        channel.setJarCache(new FileSystemJarCache(dir, true));
        channel.call(new JarCacherCallable());
        channel.jarLoader.calcChecksum(jar1);
        channel.jarLoader.calcChecksum(jar2);
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

        withChannel(channelRunner, channel -> {
            channel.jarLoader.calcChecksum(jar1);
            channel.jarLoader.calcChecksum(jar2);
            System.out.println("Channel "+ channel.getName());
            channel.jarLoader.showInfo();
        });


    }

    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testJarLoadingTest2(ChannelRunner channelRunner) throws Exception {
        assumeFalse(channelRunner instanceof InProcessCompatibilityRunner);

        withChannel(channelRunner, channel -> {
            channel.jarLoader.calcChecksum(jar1);
            channel.jarLoader.calcChecksum(jar2);
            System.out.println("Channel "+ channel.getName());
            channel.jarLoader.showInfo();
        });

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


