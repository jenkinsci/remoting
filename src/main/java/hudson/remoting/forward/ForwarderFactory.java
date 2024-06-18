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
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.SocketChannelStream;
import hudson.remoting.VirtualChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectStreamException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.remoting.Role;
import org.jenkinsci.remoting.RoleChecker;
import org.jenkinsci.remoting.SerializableOnlyOverRemoting;

/**
 * Creates {@link Forwarder}.
 *
 * @author Kohsuke Kawaguchi
 */
@Deprecated
public class ForwarderFactory {

    private static final Logger LOGGER = Logger.getLogger(ForwarderFactory.class.getName());

    /**
     * Creates a connector on the remote side that connects to the speicied host and port.
     */
    public static Forwarder create(VirtualChannel channel, final String remoteHost, final int remotePort)
            throws IOException, InterruptedException {
        return channel.call(new ForwarderCallable(remoteHost, remotePort));
    }

    public static Forwarder create(String remoteHost, int remotePort) {
        return new ForwarderImpl(remoteHost, remotePort);
    }

    private static class ForwarderImpl implements Forwarder, SerializableOnlyOverRemoting {
        private final String remoteHost;
        private final int remotePort;

        private ForwarderImpl(String remoteHost, int remotePort) {
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
        }

        @Override
        @SuppressFBWarnings(value = "UNENCRYPTED_SOCKET", justification = "Unused mechanism.")
        public OutputStream connect(OutputStream out) throws IOException {
            Socket s = new Socket(remoteHost, remotePort);
            try (InputStream in = SocketChannelStream.in(s)) {
                new CopyThread(String.format("Copier to %s:%d", remoteHost, remotePort), in, out, () -> {
                            try {
                                s.close();
                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING, "Problem closing socket for ForwardingFactory", e);
                            }
                        })
                        .start();
            }
            return new RemoteOutputStream(SocketChannelStream.out(s));
        }

        /**
         * When sent to the remote node, send a proxy.
         */
        private Object writeReplace() throws ObjectStreamException {
            return getChannelForSerialization().export(Forwarder.class, this);
        }

        private static final long serialVersionUID = 8382509901649461466L;
    }

    /**
     * Role that's willing to open a socket to arbitrary node nad forward that to the other side.
     */
    public static final Role ROLE = new Role(ForwarderFactory.class);

    private static class ForwarderCallable implements Callable<Forwarder, IOException> {

        private static final long serialVersionUID = 1L;
        private final String remoteHost;
        private final int remotePort;

        public ForwarderCallable(String remoteHost, int remotePort) {
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
        }

        @Override
        public Forwarder call() throws IOException {
            return new ForwarderImpl(remoteHost, remotePort);
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
            checker.check(this, ROLE);
        }
    }
}
