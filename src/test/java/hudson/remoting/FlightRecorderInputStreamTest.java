package hudson.remoting;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StreamCorruptedException;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * @author Kohsuke Kawaguchi
 */
public class FlightRecorderInputStreamTest {
    @Test
    public void diagnosis() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject("abc");
        oos.writeObject("def");
        oos.flush();
        baos.write(0xFF);  // corruption
        oos.writeObject("ghi");

        FlightRecorderInputStream fis = new FlightRecorderInputStream(new ByteArrayInputStream(baos.toByteArray()));
        ObjectInputStream ois = new ObjectInputStream(fis);
        assertEquals("abc", ois.readObject());
        fis.clear();
        assertEquals("def", ois.readObject());
        try {
            ois.readObject();
            fail("Expecting a corruption");
        } catch (StreamCorruptedException e) {
            DiagnosedStreamCorruptionException t = fis.analyzeCrash(e, "test");
            t.printStackTrace();
            assertNull(t.getDiagnoseFailure());
            // back buffer shouldn't contain 'abc' since the stream was reset
            assertTrue(Arrays.equals(new byte[]{TC_STRING,0,3,'d','e','f',-1}, t.getReadBack()));
            assertTrue(Arrays.equals(new byte[]{TC_STRING,0,3,'g','h','i'},t.getReadAhead()));
        }
    }

    private static final byte TC_STRING = 0x74;
}
