package hudson.remoting;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.common.hash.Hashing;
import com.google.common.io.FileWriteMode;
import com.google.common.io.Files;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

/**
 * Tests for {@link FileSystemJarCache}.
 *
 * @author Akshay Dayal
 */
public class FileSystemJarCacheTest {

    private static final String CONTENTS = "These are the contents";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Mock
    private Channel mockChannel;

    @Mock
    private JarLoader mockJarLoader;

    private FileSystemJarCache fileSystemJarCache;
    private Checksum expectedChecksum;

    private AutoCloseable closeable;

    @Before
    public void setUp() throws Exception {
        closeable = MockitoAnnotations.openMocks(this);
        fileSystemJarCache = new FileSystemJarCache(tmp.getRoot(), true);

        expectedChecksum = ChecksumTest.createdExpectedChecksum(
                Hashing.sha256().hashBytes(CONTENTS.getBytes(StandardCharsets.UTF_8)));
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    @Test
    public void testRetrieveAlreadyExists() throws Exception {
        File expectedFile = fileSystemJarCache.map(expectedChecksum.sum1, expectedChecksum.sum2);
        expectedFile.getParentFile().mkdirs();
        assertTrue(expectedFile.createNewFile());
        writeToFile(expectedFile, CONTENTS);

        URL url = fileSystemJarCache.retrieve(mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
        assertEquals(expectedFile.toURI().toURL(), url);

        // Changing the content after successfully cached is not an expected use-case.
        // Here used to verity checksums are cached.
        writeToFile(expectedFile, "Something else");
        url = fileSystemJarCache.retrieve(mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
        assertEquals(expectedFile.toURI().toURL(), url);
    }

    @Test
    public void testSuccessfulRetrieve() throws Exception {
        mockCorrectLoad();

        URL url = fileSystemJarCache.retrieve(mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
        assertEquals(expectedChecksum, Checksum.forURL(url));
    }

    @Test
    public void testRetrieveChecksumDifferent() throws Exception {
        when(mockChannel.getProperty(JarLoader.THEIRS)).thenReturn(mockJarLoader);
        doAnswer((Answer<Void>) invocationOnMock -> {
                    RemoteOutputStream o = (RemoteOutputStream) invocationOnMock.getArguments()[2];
                    o.write("Some other contents".getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(mockJarLoader)
                .writeJarTo(eq(expectedChecksum.sum1), eq(expectedChecksum.sum2), any(RemoteOutputStream.class));

        final IOException ex = assertThrows(
                IOException.class,
                () -> fileSystemJarCache.retrieve(mockChannel, expectedChecksum.sum1, expectedChecksum.sum2));
        assertThat(ex.getCause(), hasMessage(containsString("Incorrect checksum of retrieved jar")));
    }

    @Test
    @Issue("JENKINS-39547")
    public void retrieveInvalidChecksum() throws Exception {
        when(mockChannel.getProperty(JarLoader.THEIRS)).thenReturn(mockJarLoader);

        File expected = fileSystemJarCache.map(expectedChecksum.sum1, expectedChecksum.sum2);
        writeToFile(expected, "This is no going to match the checksum");

        mockCorrectLoad();

        URL url = fileSystemJarCache.retrieve(mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
        assertEquals(expectedChecksum, Checksum.forURL(url));
    }

    private void writeToFile(File expected, String content) throws IOException {
        expected.getParentFile().mkdirs();
        try (FileWriter fileWriter = new FileWriter(expected)) {
            fileWriter.write(content);
        }
    }

    @Test
    public void testRenameFailsAndNoTarget() throws Exception {
        File expectedFile = fileSystemJarCache.map(expectedChecksum.sum1, expectedChecksum.sum2);
        File spy = spy(tmp.newFile());
        FileSystemJarCache jarCache = spy(fileSystemJarCache);
        doReturn(spy).when(jarCache).createTempJar(any(File.class));

        mockCorrectLoad();

        when(spy.renameTo(expectedFile)).thenReturn(false);
        assertFalse(expectedFile.exists());

        final IOException ex = assertThrows(
                IOException.class, () -> jarCache.retrieve(mockChannel, expectedChecksum.sum1, expectedChecksum.sum2));
        assertThat(ex.getCause(), hasMessage(containsString("Unable to create")));
    }

    @Test
    public void testRenameFailsAndBadPreviousTarget() throws Exception {
        final File expectedFile = fileSystemJarCache.map(expectedChecksum.sum1, expectedChecksum.sum2);
        File fileSpy = spy(tmp.newFile());
        FileSystemJarCache jarCache = spy(fileSystemJarCache);
        doReturn(fileSpy).when(jarCache).createTempJar(any(File.class));

        mockCorrectLoad();
        doAnswer((Answer<Boolean>) invocationOnMock -> {
                    Files.createParentDirs(expectedFile);
                    expectedFile.createNewFile();
                    Files.asCharSink(expectedFile, StandardCharsets.UTF_8, FileWriteMode.APPEND)
                            .write("Some other contents");
                    return false;
                })
                .when(fileSpy)
                .renameTo(expectedFile);

        final IOException ex = assertThrows(
                IOException.class, () -> jarCache.retrieve(mockChannel, expectedChecksum.sum1, expectedChecksum.sum2));
        assertThat(ex.getCause(), hasMessage(containsString("Incorrect checksum of previous jar")));
    }

    private void mockCorrectLoad() throws IOException, InterruptedException {
        when(mockChannel.getProperty(JarLoader.THEIRS)).thenReturn(mockJarLoader);
        doAnswer((Answer<Void>) invocationOnMock -> {
                    RemoteOutputStream o = (RemoteOutputStream) invocationOnMock.getArguments()[2];
                    o.write(CONTENTS.getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(mockJarLoader)
                .writeJarTo(eq(expectedChecksum.sum1), eq(expectedChecksum.sum2), any(RemoteOutputStream.class));
    }
}
