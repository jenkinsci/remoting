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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.remoting.FileSystemJarCache;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.UnsupportedEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import org.jenkinsci.remoting.engine.WorkDirManager;
import org.jenkinsci.remoting.util.IOUtils;
import org.jenkinsci.remoting.util.PathUtils;
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
import java.nio.file.Path;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

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

    @Option(name="-noKeepAlive",
            usage="Disable TCP socket keep alive on connection to the master.")
    public boolean noKeepAlive = false;

    @Option(name = "-cert",
            usage = "Specify additional X.509 encoded PEM certificates to trust when connecting to Jenkins " +
                    "root URLs. If starting with @ then the remainder is assumed to be the name of the " +
                    "certificate file to read.")
    public List<String> candidateCertificates;

    /**
     * Specifies a destination for error logs.
     * If specified, this option overrides the default destination within {@link #workDir}.
     * If both this options and {@link #workDir} is not set, the log will not be generated.
     * @since 3.8
     */
    @Option(name="-agentLog", usage="Local agent error log destination (overrides workDir)")
    @CheckForNull
    public File agentLog = null;
    
    /**
     * Specified location of the property file with JUL settings.
     * @since 3.8
     */
    @CheckForNull
    @Option(name="-loggingConfig",usage="Path to the property file with java.util.logging settings")
    public File loggingConfigFile = null;
    
    /**
     * Specifies a default working directory of the remoting instance.
     * If specified, this directory will be used to store logs, JAR cache, etc.
     * <p>
     * In order to retain compatibility, the option is disabled by default.
     * <p>
     * Jenkins specifics: This working directory is expected to be equal to the agent root specified in Jenkins configuration.
     * @since 3.8
     */
    @Option(name = "-workDir",
            usage = "Declares the working directory of the remoting instance (stores cache and logs by default)")
    @CheckForNull
    public File workDir = null;

    /**
     * Specifies a directory within {@link #workDir}, which stores all the remoting-internal files.
     * <p>
     * This option is not expected to be used frequently, but it allows remoting users to specify a custom
     * storage directory if the default {@code remoting} directory is consumed by other stuff.
     * @since 3.8
     */
    @Option(name = "-internalDir",
            usage = "Specifies a name of the internal files within a working directory ('remoting' by default)",
            depends = "-workDir")
    @Nonnull
    public String internalDir = WorkDirManager.DirType.INTERNAL_DIR.getDefaultLocation();

    /**
     * Fail the initialization if the workDir or internalDir are missing.
     * This option presumes that the workspace structure gets initialized previously in order to ensure that we do not start up with a borked instance
     * (e.g. if a filesystem mount gets disconnected).
     * @since 3.8
     */
    @Option(name = "-failIfWorkDirIsMissing",
            usage = "Fails the initialization if the requested workDir or internalDir are missing ('false' by default)",
            depends = "-workDir")
    @Nonnull
    public boolean failIfWorkDirIsMissing = WorkDirManager.DEFAULT_FAIL_IF_WORKDIR_IS_MISSING;

    /**
     * @since 2.24
     */
    @Option(name="-jar-cache",metaVar="DIR",usage="Cache directory that stores jar files sent from the master")
    public File jarCache = null;


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
        engine.startEngine();
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
        String slaveName = args.get(1);
        LOGGER.log(INFO, "Setting up slave: {0}", slaveName);
        Engine engine = new Engine(
                headlessMode ? new CuiListener() : new GuiListener(),
                urls, args.get(0), slaveName);
        if(tunnel!=null)
            engine.setTunnel(tunnel);
        if(credentials!=null)
            engine.setCredentials(credentials);
        if(proxyCredentials!=null)
        	engine.setProxyCredentials(proxyCredentials);
        if(jarCache!=null)
            engine.setJarCache(new FileSystemJarCache(jarCache,true));
        engine.setNoReconnect(noReconnect);
        engine.setKeepAlive(!noKeepAlive);
        
        // TODO: ideally logging should be initialized before the "Setting up slave" entry
        if (agentLog != null) {
            try {
                engine.setAgentLog(PathUtils.fileToPath(agentLog));
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot retrieve custom log destination", ex);
            }
        }
        if (loggingConfigFile != null) {
            try {
                engine.setLoggingConfigFile(PathUtils.fileToPath(loggingConfigFile));
            } catch (IOException ex) {
                throw new IllegalStateException("Logging config file is invalid", ex);
            }
        }
        
        if (candidateCertificates != null && !candidateCertificates.isEmpty()) {
            CertificateFactory factory;
            try {
                factory = CertificateFactory.getInstance("X.509");
            } catch (CertificateException e) {
                throw new IllegalStateException("Java platform specification mandates support for X.509", e);
            }
            List<X509Certificate> certificates = new ArrayList<X509Certificate>(candidateCertificates.size());
            for (String certOrAtFilename : candidateCertificates) {
                certOrAtFilename = certOrAtFilename.trim();
                byte[] cert;
                if (certOrAtFilename.startsWith("@")) {
                    File file = new File(certOrAtFilename.substring(1));
                    long length;
                    if (file.isFile()
                            && (length = file.length()) < 65536
                            && length > "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----".length()) {
                        try {
                            // we do basic size validation, if there are x509 certificates that have a PEM encoding
                            // larger
                            // than 64kb we can revisit the upper bound.
                            cert = new byte[(int) length];
                            FileInputStream fis = new FileInputStream(file);
                            final int read;
                            try {
                                read = fis.read(cert);
                            } finally {
                                fis.close();
                            }
                            if (cert.length != read) {
                                LOGGER.log(Level.WARNING, "Only read {0} bytes from {1}, expected to read {2}",
                                        new Object[]{read, file, cert.length});
                                // skip it
                                continue;
                            }
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Could not read certificate from " + file, e);
                            continue;
                        }
                    } else {
                        if (file.isFile()) {
                            LOGGER.log(Level.WARNING, "Could not read certificate from {0}. File size is not within " +
                                    "the expected range for a PEM encoded X.509 certificate", file.getAbsolutePath());
                        } else {
                            LOGGER.log(Level.WARNING, "Could not read certificate from {0}. File not found",
                                    file.getAbsolutePath());
                        }
                        continue;
                    }
                } else {
                    try {
                        cert = certOrAtFilename.getBytes("US-ASCII");
                    } catch (UnsupportedEncodingException e) {
                        LOGGER.log(Level.WARNING, "Could not parse certificate " + certOrAtFilename, e);
                        continue;
                    }
                }
                try {
                    certificates.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(cert)));
                } catch (ClassCastException e) {
                    LOGGER.log(Level.WARNING, "Expected X.509 certificate from " + certOrAtFilename, e);
                } catch (CertificateException e) {
                    LOGGER.log(Level.WARNING, "Could not parse X.509 certificate from " + certOrAtFilename, e);
                }
            }
            engine.setCandidateCertificates(certificates);
        }

        // Working directory settings
        if (workDir != null) {
            try {
                engine.setWorkDir(PathUtils.fileToPath(workDir));
            } catch (IOException ex) {
                throw new IllegalStateException("Work directory path is invalid", ex);
            }
        }
        engine.setInternalDir(internalDir);
        engine.setFailIfWorkDirIsMissing(failIfWorkDirIsMissing);

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

        @SuppressFBWarnings(value = "DM_EXIT",
                justification = "Yes, we really want to exit in the case of severe error")
        public void error(Throwable t) {
            LOGGER.log(Level.SEVERE, t.getMessage(), t);
            System.exit(-1);
        }

        public void onDisconnect() {
        }

        public void onReconnect() {
        }
    }

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
}
