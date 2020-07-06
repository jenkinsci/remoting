package hudson.remoting;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;

/**
 * @author Kohsuke Kawaguchi
 */
public class DeadRemoteOutputStreamTest extends RmiTestBase implements Serializable {

    /**
     * If the remote writing end reports {@link IOException}, then the writing end shall
     * eventually see it.
     */
    public void testDeadWriterNotification() throws Exception {
        final OutputStream os = new RemoteOutputStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                System.gc();
                throw new IOException(MESSAGE, (Exception) DummyClassLoader.apply(TestCallable.class));
            }
        });

        channel.call(new DeadWriterCallable(os));
    }

    public static final String MESSAGE = "dead man walking";

    private static class DeadWriterCallable extends CallableBase<Void, Exception> {
        private final OutputStream os;

        public DeadWriterCallable(OutputStream os) {
            this.os = os;
        }

        @Override
        public Void call() throws Exception {
            os.write(0); // this write will go through because we won't notice that it's dead
            System.gc();
            Thread.sleep(1000);

            try {
                for (int i=0; i<100; i++) {
                    os.write(0);
                    System.gc();
                    Thread.sleep(10);
                }
                fail("Expected to see the failure");
            } catch (IOException e) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String whole = sw.toString();
                assertTrue(whole, whole.contains(MESSAGE) && whole.contains("hudson.rem0ting.TestCallable"));
            }
            return null;
        }
        private static final long serialVersionUID = 1L;
    }
    private static final long serialVersionUID = 1L;
}
