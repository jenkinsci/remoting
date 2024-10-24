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
package hudson.remoting.forward;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.SocketChannelStream;
import hudson.remoting.VirtualChannel;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.Role;
import org.jenkinsci.remoting.RoleChecker;

/**
 * Port forwarder over a remote channel.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.315
 */
@Deprecated
public class PortForwarder extends Thread implements Closeable, ListeningPort {
    private final Forwarder forwarder;
    private final ServerSocket socket;

    @SuppressFBWarnings(value = "UNENCRYPTED_SERVER_SOCKET", justification = "Unused")
    public PortForwarder(int localPort, Forwarder forwarder) throws IOException {
        super(String.format("Port forwarder %d", localPort));
        this.forwarder = forwarder;
        this.socket = new ServerSocket(localPort);
        // mark as a daemon thread by default.
        // the caller can explicitly cancel this by doing "setDaemon(false)"
        setDaemon(true);
        setUncaughtExceptionHandler((t, e) -> {
            LOGGER.log(Level.SEVERE, e, () -> "Uncaught exception in PortForwarder thread " + t);
            try {
                socket.close();
            } catch (IOException e1) {
                LOGGER.log(Level.SEVERE, "Could not close socket after uncaught exception");
            }
        });
    }

    @Override
    public int getPort() {
        return socket.getLocalPort();
    }

    @Override
    public void run() {
        try {
            try {
                while (true) {
                    final Socket s = socket.accept();
                    new Thread("Port forwarding session from " + s.getRemoteSocketAddress()) {
                        {
                            setUncaughtExceptionHandler((t, e) -> LOGGER.log(
                                    Level.SEVERE, e, () -> "Unhandled exception in port forwarding session " + t));
                        }

                        @Override
                        public void run() {
                            try (InputStream in = SocketChannelStream.in(s);
                                    OutputStream out =
                                            forwarder.connect(new RemoteOutputStream(SocketChannelStream.out(s)))) {
                                new CopyThread("Copier for " + s.getRemoteSocketAddress(), in, out, () -> {
                                            try {
                                                s.close();
                                            } catch (IOException e) {
                                                LOGGER.log(Level.WARNING, "Failed to close socket", e);
                                            }
                                        })
                                        .start();
                            } catch (IOException e) {
                                // this happens if the socket connection is terminated abruptly.
                                LOGGER.log(Level.FINE, "Port forwarding session was shut down abnormally", e);
                            }
                        }
                    }.start();
                }
            } finally {
                socket.close();
            }
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "Port forwarding was shut down abnormally", e);
        }
    }

    /**
     * Shuts down this port forwarder.
     */
    @Override
    public void close() throws IOException {
        interrupt();
        socket.close();
    }

    /**
     * Starts a {@link PortForwarder} accepting remotely at the given channel,
     * which connects by using the given connector.
     *
     * @return
     *      A {@link Closeable} that can be used to shut the port forwarding down.
     */
    public static ListeningPort create(VirtualChannel ch, final int acceptingPort, Forwarder forwarder)
            throws IOException, InterruptedException {
        // need a remotable reference
        final Forwarder proxy = ch.export(Forwarder.class, forwarder);

        return ch.call(new ListeningPortCallable(acceptingPort, proxy));
    }

    private static final Logger LOGGER = Logger.getLogger(PortForwarder.class.getName());

    /**
     * Role that's willing to listen on a socket and forward that to the other side.
     */
    public static final Role ROLE = new Role(PortForwarder.class);

    private static class ListeningPortCallable implements Callable<ListeningPort, IOException> {
        private static final long serialVersionUID = 1L;
        private final int acceptingPort;
        private final Forwarder proxy;

        public ListeningPortCallable(int acceptingPort, Forwarder proxy) {
            this.acceptingPort = acceptingPort;
            this.proxy = proxy;
        }

        @Override
        public ListeningPort call() throws IOException {
            final Channel channel =
                    getOpenChannelOrFail(); // We initialize it early, so the forwarder won's start its daemon if the
            // channel is shutting down
            PortForwarder t = new PortForwarder(acceptingPort, proxy);
            t.start();
            return channel.export(ListeningPort.class, t);
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
            checker.check(this, ROLE);
        }
    }
}
