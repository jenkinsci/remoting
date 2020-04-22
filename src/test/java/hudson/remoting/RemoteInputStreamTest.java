package hudson.remoting;

import junit.framework.Test;
import org.apache.commons.io.input.BrokenInputStream;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static hudson.remoting.RemoteInputStream.Flag.GREEDY;
import static hudson.remoting.RemoteInputStream.Flag.NOT_GREEDY;
import static java.util.Arrays.asList;

/**
 * @author Kohsuke Kawaguchi
 */
public class RemoteInputStreamTest extends RmiTestBase {
    /**
     * Makes sure non-greedy RemoteInputStream is not completely dead on arrival.
     */
    public void testNonGreedy() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(toBytes("12345678"));
        channel.call(new Read(new RemoteInputStream(in, NOT_GREEDY),toBytes("1234")));
        assertTrue(Arrays.equals(readFully(in, 4), "5678".getBytes()));
    }

    /**
     * Makes sure greedy RemoteInputStream is not completely dead on arrival.
     */
    public void testGreedy() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(toBytes("12345678"));
        channel.call(new Read(new RemoteInputStream(in, GREEDY),toBytes("1234")));
        // not very reliable but the intention is to have it greedily read
        Thread.sleep(100);

        if (channel.remoteCapability.supportsGreedyRemoteInputStream())
            assertEquals(-1, in.read());
        else {
            // if we are dealing with version that doesn't support GREEDY, we should be reading '5'
            assertEquals('5', in.read());
        }
    }

    /**
     * Reads N bytes and verify that it matches what's expected.
     */
    private static class Read extends CallableBase<Object,IOException> {
        private final RemoteInputStream in;
        private final byte[] expected;

        private Read(RemoteInputStream in, byte[] expected) {
            this.in = in;
            this.expected = expected;
        }

        public Object call() throws IOException {
            assertTrue(Arrays.equals(readFully(in,expected.length),expected));
            return null;
        }
        private static final long serialVersionUID = 1L;
    }


    /**
     * Read in multiple chunks.
     */
    public void testGreedy2() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(toBytes("12345678"));
        final RemoteInputStream i = new RemoteInputStream(in, GREEDY);

        channel.call(new TestGreedy2(i));
        assertEquals(in.read(),-1);
    }

    private static class TestGreedy2 extends CallableBase<Void,IOException> {
        private final RemoteInputStream i;

        public TestGreedy2(RemoteInputStream i) {
            this.i = i;
        }

        public Void call() throws IOException {
            assertEquals(readFully(i, 4), toBytes("1234"));
            assertEquals(readFully(i, 4), toBytes("5678"));
            assertEquals(i.read(),-1);
            return null;
        }
        private static final long serialVersionUID = 1L;
    }


    /**
     * Greedy {@link RemoteInputStream} should propagate error.
     */
    public void testErrorPropagation() throws Exception {
        for (RemoteInputStream.Flag f : asList(GREEDY, NOT_GREEDY)) {
            InputStream in = new SequenceInputStream(
                    new ByteArrayInputStream(toBytes("1234")),
                    new BrokenInputStream(new SkyIsFalling())
            );
            final RemoteInputStream i = new RemoteInputStream(in, f);

            channel.call(new TestErrorPropagation(i));
        }
    }

    private static class SkyIsFalling extends IOException {private static final long serialVersionUID = 1L;}

    private static class TestErrorPropagation extends CallableBase<Void, IOException> {
        private final RemoteInputStream i;

        public TestErrorPropagation(RemoteInputStream i) {
            this.i = i;
        }

        public Void call() throws IOException {
            assertEquals(readFully(i, 4), toBytes("1234"));
            try {
                i.read();
                throw new AssertionError();
            } catch (SkyIsFalling e) {
                // non-greedy implementation rethrows the same exception, which produces confusing stack trace,
                // but in case someone is using it as a signal I'm not changing that behaviour.
                return null;
            } catch (IOException e) {
                if (e.getCause() instanceof SkyIsFalling)
                    return null;
                throw e;
            }
        }
        private static final long serialVersionUID = 1L;
    }


    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] actual = new byte[n];
        new DataInputStream(in).readFully(actual);
        return actual;
    }

    private static void assertEquals(byte[] b1, byte[] b2) throws IOException {
        if (!Arrays.equals(b1,b2)) {
            fail("Expected "+ HexDump.toHex(b1)+" but got "+ HexDump.toHex(b2));
        }
    }

    private static byte[] toBytes(String s) throws UnsupportedEncodingException {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static Test suite() throws Exception {
        return buildSuite(RemoteInputStreamTest.class);
    }
}
