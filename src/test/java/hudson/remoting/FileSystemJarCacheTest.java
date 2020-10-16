package hudson.remoting;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import org.hamcrest.core.StringContains;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FileSystemJarCache}.
 *
 * @author Akshay Dayal
 */
public class FileSystemJarCacheTest {

    private static final String CONTENTS = "These are the contents";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Rule public ExpectedException expectedEx = ExpectedException.none();

    @Mock private Channel mockChannel;
    @Mock private JarLoader mockJarLoader;
    private FileSystemJarCache fileSystemJarCache;
    private Checksum expectedChecksum;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        fileSystemJarCache = new FileSystemJarCache(tmp.getRoot(), true);

        expectedChecksum = ChecksumTest.createdExpectedChecksum(
                Hashing.sha256().hashBytes(CONTENTS.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testRetrieveAlreadyExists() throws Exception {
        File expectedFile = fileSystemJarCache.map(expectedChecksum.sum1, expectedChecksum.sum2);
        expectedFile.getParentFile().mkdirs();
        assertTrue(expectedFile.createNewFile());
        writeToFile(expectedFile, CONTENTS);

        URL url = fileSystemJarCache.retrieve(
                mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
        assertEquals(expectedFile.toURI().toURL(), url);

        // Changing the content after successfully cached is not an expected use-case.
        // Here used to verity checksums are cached.
        writeToFile(expectedFile, "Something else");
        url = fileSystemJarCache.retrieve(
                mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
        assertEquals(expectedFile.toURI().toURL(), url);
    }

    @Test
    public void testSuccessfulRetrieve() throws Exception {
        mockCorrectLoad();

        URL url = fileSystemJarCache.retrieve(
                mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
        assertEquals(expectedChecksum, Checksum.forURL(url));
    }

    @Test
    public void testRetrieveChecksumDifferent() throws Exception {
        when(mockChannel.getProperty(JarLoader.THEIRS)).thenReturn(mockJarLoader);
        doAnswer((Answer<Void>) invocationOnMock -> {
            RemoteOutputStream o = (RemoteOutputStream) invocationOnMock.getArguments()[2];
            o.write("Some other contents".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(mockJarLoader).writeJarTo(
                eq(expectedChecksum.sum1),
                eq(expectedChecksum.sum2),
                any(RemoteOutputStream.class));

        expectedEx.expect(IOException.class);
        expectedEx.expectCause(hasMessage(StringContains.containsString(
                "Incorrect checksum of retrieved jar")));
        fileSystemJarCache.retrieve(
                mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
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

        expectedEx.expect(IOException.class);
        expectedEx.expectCause(hasMessage(StringContains.containsString("Unable to create")));

        jarCache.retrieve(mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
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
            Files.append("Some other contents", expectedFile, StandardCharsets.UTF_8);
            return false;
        }).when(fileSpy).renameTo(expectedFile);

        expectedEx.expect(IOException.class);
        expectedEx.expectCause(hasMessage(StringContains.containsString(
                "Incorrect checksum of previous jar")));

        jarCache.retrieve(mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
    }

    private void mockCorrectLoad() throws IOException, InterruptedException {
        when(mockChannel.getProperty(JarLoader.THEIRS)).thenReturn(mockJarLoader);
        doAnswer((Answer<Void>) invocationOnMock -> {
            RemoteOutputStream o = (RemoteOutputStream) invocationOnMock.getArguments()[2];
            o.write(CONTENTS.getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(mockJarLoader).writeJarTo(
                eq(expectedChecksum.sum1),
                eq(expectedChecksum.sum2),
                any(RemoteOutputStream.class));
    }
}
