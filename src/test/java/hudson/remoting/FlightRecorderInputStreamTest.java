package hudson.remoting;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StreamCorruptedException;
import org.junit.Test;

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
        baos.write(0xFF); // corruption
        oos.writeObject("ghi");

        FlightRecorderInputStream fis = new FlightRecorderInputStream(new ByteArrayInputStream(baos.toByteArray()));
        ObjectInputStream ois = new ObjectInputStream(fis);
        assertEquals("abc", ois.readObject());
        fis.clear();
        assertEquals("def", ois.readObject());

        final StreamCorruptedException e =
                assertThrows("Expecting a corruption", StreamCorruptedException.class, ois::readObject);
        DiagnosedStreamCorruptionException t = fis.analyzeCrash(e, "test");
        t.printStackTrace();
        assertNull(t.getDiagnoseFailure());
        // back buffer shouldn't contain 'abc' since the stream was reset
        assertArrayEquals(new byte[] {TC_STRING, 0, 3, 'd', 'e', 'f', -1}, t.getReadBack());
        assertArrayEquals(new byte[] {TC_STRING, 0, 3, 'g', 'h', 'i'}, t.getReadAhead());
    }

    @Test
    public void bounding() throws Exception {
        int sz = (int) (FlightRecorderInputStream.BUFFER_SIZE * /* not a round multiple */ 5.3);
        byte[] stuff = new byte[sz];
        for (int i = 0; i < sz; i++) {
            stuff[i] = (byte) (i % /* arbitrary cycle, not a power of 2 */ 213);
        }
        FlightRecorderInputStream fris = new FlightRecorderInputStream(new ByteArrayInputStream(stuff));
        byte[] stuff2 = new byte[sz];
        int pos = 0;
        int chunk = 117;
        while (pos < sz) {
            int toread = Math.min(chunk, sz - pos);
            assertEquals(toread, fris.read(stuff2, pos, toread));
            pos += toread;
            chunk *= 1.9; // just try various chunk sizes
        }
        assertEquals(sz, pos);
        assertArrayEquals(stuff, stuff2);
        byte[] rec = fris.getRecord();
        assertEquals(FlightRecorderInputStream.BUFFER_SIZE, rec.length);
        byte[] expected = new byte[FlightRecorderInputStream.BUFFER_SIZE];
        System.arraycopy(stuff, stuff.length - expected.length, expected, 0, expected.length);
        assertArrayEquals(expected, rec);
    }

    private static final byte TC_STRING = 0x74;
}
