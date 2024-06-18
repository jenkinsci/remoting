package hudson.remoting.throughput;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import org.apache.commons.io.IOUtils;

/**
 * @author Kohsuke Kawaguchi
 */
public class DumbReceiver {
    public static void main(String[] args) throws Exception {
        ServerSocket ss = new ServerSocket(PORT);
        while (true) {
            System.out.println("Ready");
            try (Socket s = ss.accept()) {
                System.out.println("Accepted");
                IOUtils.copy(s.getInputStream(), OutputStream.nullOutputStream());
            }
        }
    }

    public static final int PORT = 9533;
}
