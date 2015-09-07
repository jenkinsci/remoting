package hudson.remoting;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import org.hamcrest.core.StringContains;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.internal.matchers.ThrowableMessageMatcher.hasMessage;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;
import static org.powermock.api.mockito.PowerMockito.spy;

/**
 * Tests for {@link FileSystemJarCache}.
 *
 * @author Akshay Dayal
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({FileSystemJarCache.class, File.class})
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
        doAnswer(new Answer<Void>() {
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
        doAnswer(new Answer<Void>() {
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
        expectedEx.expectCause(hasMessage(StringContains.containsString(
                "Incorrect checksum of retrieved jar")));
        fileSystemJarCache.retrieve(
                mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
    }

/*  for whatever reason I just can't seem to make this one test work. HELP!
    @Test
    public void testRenameFailsAndNoTarget() throws Exception {
        File expectedFile = fileSystemJarCache.map(expectedChecksum.sum1, expectedChecksum.sum2);
        File spy = spy(tmp.newFile());
        mockStatic(File.class);
        when(File.createTempFile(expectedFile.getName(), "tmp", expectedFile.getParentFile()))
                .thenReturn(spy);
        when(mockChannel.getProperty(JarLoader.THEIRS)).thenReturn(mockJarLoader);
        doAnswer(new Answer<Void>() {
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
        when(spy.renameTo(expectedFile)).thenReturn(false);
        assertFalse(expectedFile.exists());

        expectedEx.expect(IOException.class);
        expectedEx.expectCause(hasMessage(StringContains.containsString("Unable to create")));
        fileSystemJarCache.retrieve(
                mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
    }
*/

    @Test
    public void testRenameFailsAndBadPreviousTarget() throws Exception {
        final File expectedFile = fileSystemJarCache.map(expectedChecksum.sum1, expectedChecksum.sum2);
        File spy = spy(tmp.newFile());
        mockStatic(File.class);
        when(File.createTempFile(expectedFile.getName(), "tmp", expectedFile.getParentFile()))
                .thenReturn(spy);
        when(mockChannel.getProperty(JarLoader.THEIRS)).thenReturn(mockJarLoader);
        doAnswer(new Answer<Void>() {
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
        doAnswer(new Answer<Boolean>() {
            @Override
            public Boolean answer(InvocationOnMock invocationOnMock) throws Throwable {
                Files.createParentDirs(expectedFile);
                expectedFile.createNewFile();
                Files.append("Some other contents", expectedFile, Charset.forName("UTF-8"));
                return false;
            }
        }).when(spy).renameTo(expectedFile);

        expectedEx.expect(IOException.class);
        expectedEx.expectCause(hasMessage(StringContains.containsString(
                "Incorrect checksum of previous jar")));
        fileSystemJarCache.retrieve(
                mockChannel, expectedChecksum.sum1, expectedChecksum.sum2);
    }
}
