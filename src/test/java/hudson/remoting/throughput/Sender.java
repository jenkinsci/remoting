package hudson.remoting.throughput;

import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.Future;
import hudson.remoting.Pipe;
import hudson.remoting.SocketInputStream;
import hudson.remoting.SocketOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.junit.Assert;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;

/**
 * @author Kohsuke Kawaguchi
 */
public class Sender {

    public static void main(String[] args) throws Exception {
        byte[] payload = getRandomSequence();
        byte[] digest = digest(new ByteArrayInputStream(payload));

        while (true) {
            Socket s = new Socket("127.0.0.2",Receiver.PORT);
            s.setTcpNoDelay(true);
            Channel ch = new Channel("bogus", Executors.newCachedThreadPool(),
                    new BufferedInputStream(new SocketInputStream(s)),
                    new BufferedOutputStream(new SocketOutputStream(s)));

            final Pipe p = Pipe.createLocalToRemote();
            Future<byte[]> f = ch.callAsync(new Callable<byte[], Exception>() {
                public byte[] call() throws Exception {
                    return digest(p.getIn());
                }
            });

            System.out.println("Started");
            long start = System.nanoTime();
            IOUtils.copy(new ByteArrayInputStream(payload),p.getOut());
            p.getOut().close();
            f.get();
            System.out.println("Done: "+ TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));

            assertArrayEquals(digest, f.get()); // verify the correctness of the result

            ch.close();
            ch.join();
            s.close();
        }
    }

    private static byte[] digest(InputStream in) throws NoSuchAlgorithmException, IOException {
        DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(), MessageDigest.getInstance("MD5"));
        IOUtils.copy(in, dos);
        return dos.getMessageDigest().digest();
    }

    private static byte[] getRandomSequence() {
        byte[] buf = new byte[10*1024*1024];
        new Random(0).nextBytes(buf);
        return buf;
    }
}
