package hudson.remoting;

import hudson.remoting.Channel.Mode;
import org.apache.commons.io.output.NullOutputStream;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StreamCorruptedException;
import java.io.StringWriter;

import static junit.framework.Assert.*;

/**
 * @author Kohsuke Kawaguchi
 */
public class DiagnosedStreamCorruptionExceptionTest {
    @Test
    public void exercise() throws Exception {
        byte[] payload = {
                0,0,0,0, /* binary stream preamble*/
                (byte)0xAC, (byte)0xED, 0x00, 0x05, /* object input stream header */
                1, 2, 3, 4, 5 /* bogus data */
        };

        ClassicCommandTransport ct = (ClassicCommandTransport) ClassicCommandTransport.create(Mode.BINARY, new ByteArrayInputStream(payload), new NullOutputStream(), new NullOutputStream(), getClass().getClassLoader(), new Capability());

        try {
            ct.read();
            fail();
        } catch (DiagnosedStreamCorruptionException e) {
            StringWriter s = new StringWriter();
            PrintWriter w = new PrintWriter(s);
            e.printStackTrace(w);
            w.close();

            String msg = s.toString();
            assertTrue(msg.contains("Read ahead: 02030405"));
            assertTrue(msg.contains("invalid type code: 01"));
            assertSame(StreamCorruptedException.class, e.getCause().getClass());
        }
    }
}
