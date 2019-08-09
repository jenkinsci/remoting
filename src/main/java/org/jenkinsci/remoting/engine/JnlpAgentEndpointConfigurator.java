package org.jenkinsci.remoting.engine;

import java.io.IOException;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JnlpAgentEndpointConfigurator extends EndpointResolver {

    private static final Logger LOGGER = Logger.getLogger(JnlpAgentEndpointConfigurator.class.getName());

    private String instanceIdentity;
    private Set<String> protocols;
    private URL directUrl;

    public JnlpAgentEndpointConfigurator(URL directUrl, String instanceIdentity, Set<String> protocols) {
        this.directUrl = directUrl;
        this.instanceIdentity = instanceIdentity;
        this.protocols = protocols;
    }

    @Override
    public JnlpAgentEndpoint resolve() throws IOException {
        RSAPublicKey identity;
        try {
            identity = getIdentity(instanceIdentity);
            if (identity == null) {
                throw new IOException("Invalid instanceIdentity.");
            }
        } catch (InvalidKeySpecException e) {
            throw new IOException("Invalid instanceIdentity.");
        }

        return new JnlpAgentEndpoint(directUrl.getHost(), directUrl.getPort(), identity, protocols, null);
    }

    @Override
    public void waitForReady() throws InterruptedException {
        LOGGER.log(Level.INFO, "Sleeping 10s before reconnect.");
    }

}
