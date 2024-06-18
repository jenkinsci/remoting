package hudson.remoting;

import static org.junit.Assert.assertEquals;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs a channel in a separate JVM by launching a new JVM.
 */
public class ForkRunner implements ChannelRunner {
    private Process proc;
    private ExecutorService executor;
    private Copier copier;

    protected List<String> buildCommandLine() {
        String cp = getClasspath();

        List<String> r = new ArrayList<>();
        r.add("-Xmx128M");
        r.add("-cp");
        r.add(cp);
        r.add(Launcher.class.getName());
        return r;
    }

    @Override
    public Channel start() throws Exception {
        List<String> cmds = buildCommandLine();
        cmds.add(0, "java");
        proc = Runtime.getRuntime().exec(cmds.toArray(new String[0]));

        copier = new Copier("copier", proc.getErrorStream(), System.out);
        copier.start();

        executor = Executors.newCachedThreadPool();
        OutputStream out = proc.getOutputStream();
        return new ChannelBuilder("north", executor).build(proc.getInputStream(), out);
    }

    @Override
    public void stop(Channel channel) throws Exception {
        channel.close();
        channel.join(10 * 1000);

        executor.shutdown();

        copier.join();
        int r = proc.waitFor();

        assertEquals("exit code should have been 0", 0, r);
    }

    @Override
    public String getName() {
        return "fork";
    }

    public String getClasspath() {
        return System.getProperty("java.class.path");
    }

    @Override
    public String toString() {
        return getName();
    }
}
