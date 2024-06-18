package hudson.remoting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.io.input.BrokenInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Kohsuke Kawaguchi
 */
public class RemoteInputStreamTest {
    /**
     * Makes sure non-greedy RemoteInputStream is not completely dead on arrival.
     */
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testNonGreedy(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            ByteArrayInputStream in = new ByteArrayInputStream(toBytes("12345678"));
            channel.call(new Read(new RemoteInputStream(in, RemoteInputStream.Flag.NOT_GREEDY), toBytes("1234")));
            assertArrayEquals(readFully(in, 4), "5678".getBytes());
        });
    }

    /**
     * Makes sure greedy RemoteInputStream is not completely dead on arrival.
     */
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testGreedy(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            ByteArrayInputStream in = new ByteArrayInputStream(toBytes("12345678"));
            channel.call(new Read(new RemoteInputStream(in, RemoteInputStream.Flag.GREEDY), toBytes("1234")));
            // not very reliable but the intention is to have it greedily read
            Thread.sleep(100);

            if (channel.remoteCapability.supportsGreedyRemoteInputStream()) {
                assertEquals(-1, in.read());
            } else {
                // if we are dealing with version that doesn't support GREEDY, we should be reading '5'
                assertEquals('5', in.read());
            }
        });
    }

    /**
     * Reads N bytes and verify that it matches what's expected.
     */
    private static class Read extends CallableBase<Object, IOException> {
        private final RemoteInputStream in;
        private final byte[] expected;

        private Read(RemoteInputStream in, byte[] expected) {
            this.in = in;
            this.expected = expected;
        }

        @Override
        public Object call() throws IOException {
            assertArrayEquals(readFully(in, expected.length), expected);
            return null;
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * Read in multiple chunks.
     */
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testGreedy2(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            ByteArrayInputStream in = new ByteArrayInputStream(toBytes("12345678"));
            final RemoteInputStream i = new RemoteInputStream(in, RemoteInputStream.Flag.GREEDY);

            channel.call(new TestGreedy2(i));
            assertEquals(-1, in.read());
        });
    }

    private static class TestGreedy2 extends CallableBase<Void, IOException> {
        private final RemoteInputStream i;

        public TestGreedy2(RemoteInputStream i) {
            this.i = i;
        }

        @Override
        public Void call() throws IOException {
            assertArrayEquals(readFully(i, 4), toBytes("1234"));
            assertArrayEquals(readFully(i, 4), toBytes("5678"));
            assertEquals(-1, i.read());
            return null;
        }

        private static final long serialVersionUID = 1L;
    }

    /**
     * Greedy {@link RemoteInputStream} should propagate error.
     */
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void testErrorPropagation(ChannelRunner channelRunner) throws Exception {
        channelRunner.withChannel(channel -> {
            for (RemoteInputStream.Flag f : List.of(RemoteInputStream.Flag.GREEDY, RemoteInputStream.Flag.NOT_GREEDY)) {
                InputStream in = new SequenceInputStream(
                        new ByteArrayInputStream(toBytes("1234")), new BrokenInputStream(new SkyIsFalling()));
                final RemoteInputStream i = new RemoteInputStream(in, f);

                channel.call(new TestErrorPropagation(i));
            }
        });
    }

    private static class SkyIsFalling extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static class TestErrorPropagation extends CallableBase<Void, IOException> {
        private final RemoteInputStream i;

        public TestErrorPropagation(RemoteInputStream i) {
            this.i = i;
        }

        @Override
        public Void call() throws IOException {
            assertArrayEquals(readFully(i, 4), toBytes("1234"));
            try {
                i.read();
                throw new AssertionError();
            } catch (SkyIsFalling e) {
                // non-greedy implementation rethrows the same exception, which produces confusing stack trace,
                // but in case someone is using it as a signal I'm not changing that behaviour.
                return null;
            } catch (IOException e) {
                if (e.getCause() instanceof SkyIsFalling) {
                    return null;
                }
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

    private static void assertArrayEquals(byte[] b1, byte[] b2) {
        if (!Arrays.equals(b1, b2)) {
            fail("Expected " + HexDump.toHex(b1) + " but got " + HexDump.toHex(b2));
        }
    }

    private static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
