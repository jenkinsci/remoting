package hudson.remoting;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Runs a channel in the same JVM.
 *
 * This is the simplest test moe.
 */
public class InProcessRunner implements DualSideChannelRunner {
    private ExecutorService executor;
    /**
     * failure occurred in the other {@link Channel}.
     */
    private Exception failure;

    private Channel south;

    @Override
    public Channel start() throws Exception {
        final FastPipedInputStream in1 = new FastPipedInputStream();
        final FastPipedOutputStream out1 = new FastPipedOutputStream(in1);

        final FastPipedInputStream in2 = new FastPipedInputStream();
        final FastPipedOutputStream out2 = new FastPipedOutputStream(in2);

        final SynchronousQueue<Channel> southHandoff = new SynchronousQueue<>();

        executor = Executors.newCachedThreadPool();

        Thread t = new Thread("south bridge runner") {
            @Override
            public void run() {
                try {
                    Channel south = configureSouth().build(in2, out1);
                    southHandoff.put(south);
                    south.join();
                    System.out.println("south completed");
                } catch (Exception e) {
                    e.printStackTrace();
                    failure = e;
                }
            }
        };
        t.start();

        Channel north = configureNorth().build(in1, out2);
        south = southHandoff.poll(10, TimeUnit.SECONDS);
        return north;
    }

    protected ChannelBuilder configureNorth() {
        return new ChannelBuilder("north", executor)
                .withMode(Channel.Mode.BINARY)
                .withCapability(createCapability());
    }

    protected ChannelBuilder configureSouth() {
        return new ChannelBuilder("south", executor)
                .withMode(Channel.Mode.BINARY)
                .withCapability(createCapability());
    }

    @Override
    public void stop(Channel channel) throws Exception {
        channel.close();
        channel.join();

        System.out.println("north completed");

        executor.shutdown();

        if (failure != null) {
            throw failure; // report a failure in the south side
        }
    }

    @Override
    public String getName() {
        return "local";
    }

    @Override
    public Channel getOtherSide() {
        return south;
    }

    protected Capability createCapability() {
        return new Capability();
    }

    @Override
    public String toString() {
        return getName();
    }
}
