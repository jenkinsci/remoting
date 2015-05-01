package hudson.remoting;

import com.google.common.hash.Hashing;
import org.hamcrest.core.StringContains;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FileSystemJarCache}.
 *
 * @author Akshay Dayal
 */
@RunWith(MockitoJUnitRunner.class)
public class FileSystemJarCacheTest {

    private static final String CONTENTS = "These are the contents";

    @Rule public ExpectedException expectedEx = ExpectedException.none();
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Mock private Channel mockChannel;
    @Mock private JarLoader mockJarLoader;
    private FileSystemJarCache fileSystemJarCache;
    private Checksum expectedChecksum;

    @Before
    public void setUp() throws Exception {
        fileSystemJarCache = new FileSystemJarCache(tmp.getRoot(), true);

        expectedChecksum = ChecksumTest.createdExpectedChecksum(
                Hashing.sha256().hashBytes(CONTENTS.getBytes(Charset.forName("UTF-8"))));
    }

    @Test
    public void testRetrieveAlreadyExists() throws Exception {
        File expectedFile = fileSystemJarCache.map(expectedChecksum.sum1, expectedChecksum.sum2);
        expectedFile.getParentFile().mkdirs();
        assertTrue(expectedFile.createNewFile());

        URL url = fileSystemJarCache.retrieve(
                mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
        assertEquals(expectedFile.toURI().toURL(), url);
    }

    @Test
    public void testSuccessfulRetrieve() throws Exception {
        when(mockChannel.getProperty(JarLoader.THEIRS)).thenReturn(mockJarLoader);
        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocationOnMock) throws Throwable {
                RemoteOutputStream o = (RemoteOutputStream) invocationOnMock.getArguments()[2];
                o.write(CONTENTS.getBytes(Charset.forName("UTF-8")));
                return null;
            }
        }).when(mockJarLoader).writeJarTo(
                eq(expectedChecksum.sum1),
                eq(expectedChecksum.sum2),
                any(RemoteOutputStream.class));

        URL url = fileSystemJarCache.retrieve(
                mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
        assertEquals(expectedChecksum, Checksum.forURL(url));
    }

    @Test
    public void testRetrieveChecksumDifferent() throws Exception {
        when(mockChannel.getProperty(JarLoader.THEIRS)).thenReturn(mockJarLoader);
        Mockito.doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocationOnMock) throws Throwable {
                RemoteOutputStream o = (RemoteOutputStream) invocationOnMock.getArguments()[2];
                o.write("Some other contents".getBytes(Charset.forName("UTF-8")));
                return null;
            }
        }).when(mockJarLoader).writeJarTo(
                eq(expectedChecksum.sum1),
                eq(expectedChecksum.sum2),
                any(RemoteOutputStream.class));

        expectedEx.expect(IOException.class);
        expectedEx.expectCause(hasMessage(StringContains.containsString("Incorrect checksum")));
        fileSystemJarCache.retrieve(
                mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
    }
}
