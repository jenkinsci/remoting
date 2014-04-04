package hudson.remoting;

import hudson.remoting.Channel.Mode;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs a channel in the same JVM.
 *
 * This is the simplest test moe.
 */
public class InProcessRunner implements ChannelRunner {
    private ExecutorService executor;
    /**
     * failure occurred in the other {@link Channel}.
     */
    private Exception failure;

    public Channel start() throws Exception {
        final FastPipedInputStream in1 = new FastPipedInputStream();
        final FastPipedOutputStream out1 = new FastPipedOutputStream(in1);

        final FastPipedInputStream in2 = new FastPipedInputStream();
        final FastPipedOutputStream out2 = new FastPipedOutputStream(in2);

        executor = Executors.newCachedThreadPool();

        Thread t = new Thread("south bridge runner") {
            public void run() {
                try {
                    Channel s = new Channel("south", executor, Mode.BINARY, in2, out1, null, false, null, createCapability());
                    s.join();
                    System.out.println("south completed");
                } catch (IOException e) {
                    e.printStackTrace();
                    failure = e;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    failure = e;
                }
            }
        };
        t.start();

        return new Channel("north", executor, Mode.BINARY, in1, out2, null, false, null, createCapability());
    }

    public void stop(Channel channel) throws Exception {
        channel.close();
        channel.join();

        System.out.println("north completed");

        executor.shutdown();

        if(failure!=null)
            throw failure;  // report a failure in the south side
    }

    public String getName() {
        return "local";
    }

    protected Capability createCapability() {
        return new Capability();
    }
}
