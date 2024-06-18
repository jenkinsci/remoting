package hudson.remoting.throughput;

import static org.junit.Assert.assertArrayEquals;

import hudson.remoting.CallableBase;
import hudson.remoting.Channel;
import hudson.remoting.ChannelBuilder;
import hudson.remoting.Future;
import hudson.remoting.Pipe;
import hudson.remoting.SocketChannelStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.IOUtils;

/**
 * @author Kohsuke Kawaguchi
 */
public class Sender {

    public static void main(String[] args) throws Exception {
        byte[] payload = getRandomSequence();
        byte[] digest = digest(new ByteArrayInputStream(payload));

        while (true) {
            Socket s = new Socket("127.0.0.2", Receiver.PORT);
            s.setTcpNoDelay(true);
            Channel ch = new ChannelBuilder("bogus", Executors.newCachedThreadPool())
                    .build(
                            new BufferedInputStream(SocketChannelStream.in(s)),
                            new BufferedOutputStream(SocketChannelStream.out(s)));

            final Pipe p = Pipe.createLocalToRemote();
            Future<byte[]> f = ch.callAsync(new DigestCallable(p));

            System.out.println("Started");
            long start = System.nanoTime();
            IOUtils.copy(new ByteArrayInputStream(payload), p.getOut());
            p.getOut().close();
            f.get();
            System.out.println("Done: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

            assertArrayEquals(digest, f.get()); // verify the correctness of the result

            ch.close();
            ch.join();
            s.close();
        }
    }

    private static byte[] digest(InputStream in) throws NoSuchAlgorithmException, IOException {
        DigestOutputStream dos =
                new DigestOutputStream(OutputStream.nullOutputStream(), MessageDigest.getInstance("MD5"));
        IOUtils.copy(in, dos);
        return dos.getMessageDigest().digest();
    }

    private static byte[] getRandomSequence() {
        byte[] buf = new byte[10 * 1024 * 1024];
        new Random(0).nextBytes(buf);
        return buf;
    }

    private static class DigestCallable extends CallableBase<byte[], Throwable> {
        private final Pipe p;

        public DigestCallable(Pipe p) {
            this.p = p;
        }

        @Override
        public byte[] call() throws Exception {
            return digest(p.getIn());
        }
    }
}
