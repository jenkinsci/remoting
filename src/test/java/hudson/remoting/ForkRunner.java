package hudson.remoting;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.TeeOutputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * Runs a channel in a separate JVM by launching a new JVM.
 */
public class ForkRunner implements ChannelRunner {
    private Process proc;
    private ExecutorService executor;
    private Copier copier;

    protected List<String> buildCommandLine() {
        String cp = getClasspath();

        System.out.println(cp);
        List<String> r = new ArrayList<String>();
        r.add("-cp");
        r.add(cp);
        r.add(Launcher.class.getName());
        return r;
    }

    public Channel start() throws Exception {
        System.out.println("forking a new process");
        // proc = Runtime.getRuntime().exec("java -Xdebug -Xrunjdwp:transport=dt_socket,server=y,address=8000 hudson.remoting.Launcher");

        List<String> cmds = buildCommandLine();
        cmds.add(0,"java");
        proc = Runtime.getRuntime().exec(cmds.toArray(new String[0]));

        copier = new Copier("copier",proc.getErrorStream(),System.out);
        copier.start();

        executor = Executors.newCachedThreadPool();
        OutputStream out = proc.getOutputStream();
        if (RECORD_OUTPUT) {
            File f = File.createTempFile("remoting",".log");
            System.out.println("Recording to "+f);
            out = new TeeOutputStream(out,new FileOutputStream(f));
        }
        return new ChannelBuilder("north", executor).build(proc.getInputStream(), out);
    }

    public void stop(Channel channel) throws Exception {
        channel.close();
        channel.join(10*1000);

//            System.out.println("north completed");

        executor.shutdown();

        copier.join();
        int r = proc.waitFor();
//            System.out.println("south completed");

        assertEquals("exit code should have been 0", 0, r);
    }

    public String getName() {
        return "fork";
    }

    public String getClasspath() {
        // this assumes we run in Maven
        StringBuilder buf = new StringBuilder();
        URLClassLoader ucl = (URLClassLoader)getClass().getClassLoader();
        for (URL url : ucl.getURLs()) {
            if (buf.length()>0) buf.append(File.pathSeparatorChar);
            buf.append(FileUtils.toFile(url)); // assume all of them are file URLs
        }
        return buf.toString();
    }

    /**
     * Record the communication to the remote node. Used during debugging.
     */
    private static boolean RECORD_OUTPUT = false;
}
