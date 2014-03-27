package hudson.remoting;

import hudson.remoting.RemoteInputStream.Flag;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * @author Kohsuke Kawaguchi
 */
public class RemoteInputStreamTest extends RmiTestBase {
    /**
     * Makes sure non-greedy RemoteInputStream is not completely dead on arrival.
     */
    public void testNonGreedy() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream("12345678".getBytes());
        channel.call(new Read(RemoteInputStream.of(in, Flag.NOT_GREEDY),"1234".getBytes()));
        assertTrue(Arrays.equals(readFully(in, 4), "5678".getBytes()));
    }

    /**
     * Makes sure greedy RemoteInputStream is not completely dead on arrival.
     */
    public void testGreedy() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream("12345678".getBytes());
        channel.call(new Read(RemoteInputStream.of(in, Flag.GREEDY),"1234".getBytes()));
        // not very reliable but the intention is to have it greedily read
        Thread.sleep(100);

        assertEquals(in.read(), -1);
    }

    /**
     * Reads N bytes and verify that it matches what's expected.
     */
    private static class Read implements Callable<Object,IOException> {
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
    }


    /**
     * Read in multiple chunks.
     */
    public void testGreedy2() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream("12345678".getBytes());
        final RemoteInputStream i = RemoteInputStream.of(in, Flag.GREEDY);

        channel.call(new TestGreedy2(i));
        assertEquals(in.read(),-1);
    }

    private static class TestGreedy2 implements Callable<Void,IOException> {
        private final RemoteInputStream i;

        public TestGreedy2(RemoteInputStream i) {
            this.i = i;
        }

        public Void call() throws IOException {
            assertTrue(Arrays.equals(readFully(i, 4), "1234".getBytes()));
            assertTrue(Arrays.equals(readFully(i, 4), "5678".getBytes()));
            assertEquals(i.read(),-1);
            return null;
        }
    }



    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] actual = new byte[n];
        new DataInputStream(in).readFully(actual);
        return actual;
    }
}
