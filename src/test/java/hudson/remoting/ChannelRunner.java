/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, InfraDNA, Inc.
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
package hudson.remoting;

import hudson.remoting.Channel.Mode;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.TeeOutputStream;
import org.jenkinsci.remoting.nio.NioChannelHub;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.nio.channels.SelectionKey.*;
import static org.junit.Assert.*;

/**
 * Hides the logic of starting/stopping a channel for test.
 *
 * @author Kohsuke Kawaguchi
 */
interface ChannelRunner {
    Channel start() throws Exception;
    void stop(Channel channel) throws Exception;
    String getName();

    /**
     * Runs a channel in the same JVM.
     */
    static class InProcess implements ChannelRunner {
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

    static class InProcessCompatibilityMode extends InProcess {
        public String getName() {
            return "local-compatibility";
        }

        @Override
        protected Capability createCapability() {
            return Capability.NONE;
        }
    }

    /**
     * Runs a channel over NIO+socket.
     */
    static class NioSocket implements ChannelRunner {
        private ExecutorService executor = Executors.newCachedThreadPool();
        private NioChannelHub nio;
        /**
         * failure occurred in the other {@link Channel}.
         */
        private Throwable failure;

        private Channel south;

        public Channel start() throws Exception {
            ServerSocketChannel ss = ServerSocketChannel.open();
            ss.configureBlocking(false);
            ss.socket().bind(null);

            nio = new NioChannelHub(115) {
                @Override
                protected void onSelected(SelectionKey key) {
                    try {
                        ServerSocketChannel ss = (ServerSocketChannel) key.channel();
                        LOGGER.info("Acccepted");
                        final SocketChannel con = ss.accept();
                        executor.submit(new Runnable() {
                            public void run() {
                                try {
                                    Socket socket = con.socket();
                                    assertNull(south);
                                    south = newChannelBuilder("south", executor)
                                            .withHeaderStream(System.out)
                                            .build(socket);
                                    LOGGER.info("Connected to " + south);
                                } catch (IOException e) {
                                    LOGGER.log(Level.WARNING, "Handshake failed", e);
                                    failure = e;
                                }
                            }
                        });
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to accept a socket",e);
                        failure = e;
                    }
                }
            };
            ss.register(nio.getSelector(), OP_ACCEPT);
            LOGGER.info("Waiting for connection");
            executor.submit(new Runnable() {
                public void run() {
                    try {
                        nio.run();
                    } catch (Throwable e) {
                        LOGGER.log(Level.WARNING, "Faield to keep the NIO selector thread going",e);
                        failure = e;
                    }
                }
            });

            // create a client channel that connects to the same hub
            SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", ss.socket().getLocalPort()));
            return nio.newChannelBuilder("north",executor).withMode(Mode.BINARY).build(client);
        }

        public void stop(Channel channel) throws Exception {
            channel.close();
            channel.join();

            System.out.println("north completed");

            // we initiate the shutdown from north, so by the time it closes south should be all closed, too
            assertTrue(south.isInClosed());
            assertTrue(south.isOutClosed());

            executor.shutdown();

            if(failure!=null)
                throw new AssertionError(failure);  // report a failure in the south side
        }

        public String getName() {
            return "NIO+socket";
        }

        private static final Logger LOGGER = Logger.getLogger(NioSocket.class.getName());
    }

    /**
     * Runs a channel in a separate JVM by launching a new JVM.
     */
    static class Fork implements ChannelRunner {
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
            return new Channel("north", executor, proc.getInputStream(), out);
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

    /**
     * This will test an ASCII incompatible encoding
     */
    public static class ForkEBCDIC extends Fork {
        @Override
        protected List<String> buildCommandLine() {
            List<String> r = super.buildCommandLine();
            r.add(0,"-Dfile.encoding=CP037");
            return r;
        }
    }
}
