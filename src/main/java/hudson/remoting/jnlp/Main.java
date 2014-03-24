/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting.jnlp;

import hudson.remoting.FileSystemJarCache;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;

import java.io.File;
import java.util.logging.Logger;
import java.util.logging.Level;
import static java.util.logging.Level.INFO;
import java.util.List;
import java.util.ArrayList;
import java.net.URL;
import java.io.IOException;

import hudson.remoting.Engine;
import hudson.remoting.EngineListener;

/**
 * Entry point to JNLP slave agent.
 *
 * <p>
 * See also <tt>slave-agent.jnlp.jelly</tt> in the core.
 *
 * @author Kohsuke Kawaguchi
 */
public class Main {

    @Option(name="-tunnel",metaVar="HOST:PORT",
            usage="Connect to the specified host and port, instead of connecting directly to Jenkins. " +
                  "Useful when connection to Hudson needs to be tunneled. Can be also HOST: or :PORT, " +
                  "in which case the missing portion will be auto-configured like the default behavior")
    public String tunnel;

    @Option(name="-headless",
            usage="Run in headless mode, without GUI")
    public boolean headlessMode = Boolean.getBoolean("hudson.agent.headless")
                    || Boolean.getBoolean("hudson.webstart.headless");

    @Option(name="-url",
            usage="Specify the Jenkins root URLs to connect to.")
    public final List<URL> urls = new ArrayList<URL>();

    @Option(name="-credentials",metaVar="USER:PASSWORD",
            usage="HTTP BASIC AUTH header to pass in for making HTTP requests.")
    public String credentials;

    @Option(name="-proxyCredentials",metaVar="USER:PASSWORD",usage="HTTP BASIC AUTH header to pass in for making HTTP authenticated proxy requests.")
    public String proxyCredentials = null;

    @Option(name="-noreconnect",
            usage="If the connection ends, don't retry and just exit.")
    public boolean noReconnect = false;

    /**
     * @since 2.24
     */
    @Option(name="-jar-cache",metaVar="DIR",usage="Cache directory that stores jar files sent from the master")
    public File jarCache = null;

    /**
     * @since TODO: define a version
     */
    @Option(name="-jar-cache-disabled",usage="Disables the jar caching")
    public boolean jarCacheDisabled = false;
    
    /**
     * 4 mandatory parameters.
     * Host name (deprecated), Jenkins URL, secret key, and slave name.
     */
    @Argument
    public final List<String> args = new ArrayList<String>();

    public static void main(String[] args) throws IOException, InterruptedException {
        try {
            _main(args);
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println("java -jar slave.jar [options...] <secret key> <slave name>");
            new CmdLineParser(new Main()).printUsage(System.err);
        }
    }

    /**
     * Main without the argument handling.
     */
    public static void _main(String[] args) throws IOException, InterruptedException, CmdLineException {
        // see http://forum.java.sun.com/thread.jspa?threadID=706976&tstart=0
        // not sure if this is the cause, but attempting to fix
        // https://hudson.dev.java.net/issues/show_bug.cgi?id=310
        // by overwriting the security manager.
        try {
            System.setSecurityManager(null);
        } catch (SecurityException e) {
            // ignore and move on.
            // some user reported that this happens on their JVM: http://d.hatena.ne.jp/tueda_wolf/20080723
        }

        // if we run in Mac, put the menu bar where the user expects it
        System.setProperty("apple.laf.useScreenMenuBar", "true");

        Main m = new Main();
        CmdLineParser p = new CmdLineParser(m);
        p.parseArgument(args);
        if(m.args.size()!=2)
            throw new CmdLineException("two arguments required, but got "+m.args);
        if(m.urls.isEmpty())
            throw new CmdLineException("At least one -url option is required.");

        m.main();
    }

    public void main() throws IOException, InterruptedException {
        Engine engine = createEngine();
        engine.start();
        try {
            engine.join();
            LOGGER.fine("Engine has died");
        } finally {
            // if we are programmatically driven by other code,
            // allow them to interrupt our blocking main thread
            // to kill the on-going connection to Jenkins
            engine.interrupt();
        }
    }

    public Engine createEngine() {
        Engine engine = new Engine(
                headlessMode ? new CuiListener() : new GuiListener(),
                urls, args.get(0), args.get(1));
        if(tunnel!=null)
            engine.setTunnel(tunnel);
        if(credentials!=null)
            engine.setCredentials(credentials);
        if(proxyCredentials!=null)
        	engine.setProxyCredentials(proxyCredentials);
        if(jarCache!=null && !jarCacheDisabled) {
            engine.setJarCache(new FileSystemJarCache(jarCache,true));
        } else {
            engine.setJarCache(null);
        }
        engine.setNoReconnect(noReconnect);
        return engine;
    }

    /**
     * {@link EngineListener} implementation that sends output to {@link Logger}.
     */
    private static final class CuiListener implements EngineListener {
        private CuiListener() {
            LOGGER.info("Jenkins agent is running in headless mode.");
        }

        public void status(String msg, Throwable t) {
            LOGGER.log(INFO,msg,t);
        }

        public void status(String msg) {
            status(msg,null);
        }

        public void error(Throwable t) {
            LOGGER.log(Level.SEVERE, t.getMessage(), t);
            System.exit(-1);
        }

        public void onDisconnect() {
        }
    }

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
}
