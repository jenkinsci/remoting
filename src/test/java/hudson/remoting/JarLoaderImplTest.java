package hudson.remoting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarLoaderImplTest {

    @Test
    void testSubsequentCallersUseTheCachedChecksum(@TempDir Path tempDir) throws IOException {
        // create temp file to checksum
        Path tmpFile = tempDir.resolve("fake.jar");
        Files.writeString(tmpFile, "nothing of interest", StandardOpenOption.CREATE_NEW);
        URI uri = tmpFile.toUri();

        JarLoaderImpl jarLoaderImpl = new JarLoaderImpl();
        Checksum calcChecksum = jarLoaderImpl.calcChecksum(uri);
        // delete the file so no I/O can be performed on it, to show that the cache is used.
        Files.delete(tmpFile);
        assertEquals(calcChecksum, jarLoaderImpl.calcChecksum(uri));

        // and other JarLoaders should also use the same cache
        assertEquals(calcChecksum, new JarLoaderImpl().calcChecksum(uri));
    }
}
