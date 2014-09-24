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

import hudson.remoting.Callable;
import hudson.remoting.Channel;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.SocketChannelStream;
import hudson.remoting.VirtualChannel;
import org.jenkinsci.remoting.Role;
import org.jenkinsci.remoting.RoleChecker;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Logger;

import static java.util.logging.Level.*;

/**
 * Port forwarder over a remote channel.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.315
 */
public class PortForwarder extends Thread implements Closeable, ListeningPort {
    private final Forwarder forwarder;
    private final ServerSocket socket;

    public PortForwarder(int localPort, Forwarder forwarder) throws IOException {
        super(String.format("Port forwarder %d",localPort));
        this.forwarder = forwarder;
        this.socket = new ServerSocket(localPort);
        // mark as a daemon thread by default.
        // the caller can explicitly cancel this by doing "setDaemon(false)"
        setDaemon(true);
    }

    public int getPort() {
        return socket.getLocalPort();
    }

    @Override
    public void run() {
        try {
            try {
                while(true) {
                    final Socket s = socket.accept();
                    new Thread("Port forwarding session from "+s.getRemoteSocketAddress()) {
                        public void run() {
                            try {
                                final OutputStream out = forwarder.connect(new RemoteOutputStream(SocketChannelStream.out(s)));
                                new CopyThread("Copier for "+s.getRemoteSocketAddress(),
                                        SocketChannelStream.in(s), out).start();
                            } catch (IOException e) {
                                // this happens if the socket connection is terminated abruptly.
                                LOGGER.log(FINE,"Port forwarding session was shut down abnormally",e);
                            }
                        }
                    }.start();
                }
            } finally {
                socket.close();
            }
        } catch (IOException e) {
            LOGGER.log(FINE,"Port forwarding was shut down abnormally",e);
        }
    }

    /**
     * Shuts down this port forwarder.
     */
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
    public static ListeningPort create(VirtualChannel ch, final int acceptingPort, Forwarder forwarder) throws IOException, InterruptedException {
        // need a remotable reference
        final Forwarder proxy = ch.export(Forwarder.class, forwarder);

        return ch.call(new Callable<ListeningPort,IOException>() {
            public ListeningPort call() throws IOException {
                PortForwarder t = new PortForwarder(acceptingPort, proxy);
                t.start();
                return Channel.current().export(ListeningPort.class,t);
            }

            @Override
            public void checkRoles(RoleChecker checker) throws SecurityException {
                checker.check(this,ROLE);
            }
        });
    }

    private static final Logger LOGGER = Logger.getLogger(PortForwarder.class.getName());

    /**
     * Role that's willing to listen on a socket and forward that to the other side.
     */
    public static final Role ROLE = new Role(PortForwarder.class);
}
