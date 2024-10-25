/*
 * The MIT License
 *
 * Copyright (c) 2019, CloudBees, Inc.
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
package org.jenkinsci.remoting.engine;

import hudson.remoting.EngineListenerSplitter;
import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Set;
import java.util.logging.Logger;

public class JnlpAgentEndpointConfigurator extends JnlpEndpointResolver {

    private static final Logger LOGGER = Logger.getLogger(JnlpAgentEndpointConfigurator.class.getName());

    private final String instanceIdentity;
    private final Set<String> protocols;
    private final String directionConnection;
    private final String proxyCredentials;
    private final EngineListenerSplitter events;

    public JnlpAgentEndpointConfigurator(
            String directConnection,
            String instanceIdentity,
            Set<String> protocols,
            String proxyCredentials,
            EngineListenerSplitter events) {
        this.directionConnection = directConnection;
        this.instanceIdentity = instanceIdentity;
        this.protocols = protocols;
        this.proxyCredentials = proxyCredentials;
        this.events = events;
    }

    @Override
    public JnlpAgentEndpoint resolve() throws IOException {
        events.status("Using direct connection to " + directionConnection);
        RSAPublicKey identity;
        try {
            identity = getIdentity(instanceIdentity);
            if (identity == null) {
                throw new IOException("Invalid instanceIdentity.");
            }
        } catch (InvalidKeySpecException e) {
            throw new IOException("Invalid instanceIdentity.");
        }
        HostPort hostPort = new HostPort(directionConnection);

        return new JnlpAgentEndpoint(
                hostPort.getHost(), hostPort.getPort(), identity, protocols, null, proxyCredentials);
    }

    @Override
    public void waitForReady() {}
}
