package hudson.remoting.throughput;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;

import java.net.ServerSocket;
import java.net.Socket;

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
                IOUtils.copy(s.getInputStream(), NullOutputStream.NULL_OUTPUT_STREAM);
            }
        }
    }
    public static final int PORT = 9533;
}
