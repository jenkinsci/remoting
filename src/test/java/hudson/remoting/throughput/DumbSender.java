package hudson.remoting.throughput;

import java.io.ByteArrayInputStream;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.IOUtils;

/**
 * @author Kohsuke Kawaguchi
 */
public class DumbSender {
    public static void main(String[] args) throws Exception {
        byte[] payload = getRandomSequence();

        for (int i = 0; i < 2; i++) {
            try (Socket s = new Socket("127.0.0.2", DumbReceiver.PORT)) {
                System.out.println("Started");
                long start = System.nanoTime();
                IOUtils.copy(new ByteArrayInputStream(payload), s.getOutputStream());
                System.out.println("Done: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        }
    }

    private static byte[] getRandomSequence() {
        byte[] buf = new byte[10 * 1024 * 1024];
        new Random(0).nextBytes(buf);
        return buf;
    }
}
