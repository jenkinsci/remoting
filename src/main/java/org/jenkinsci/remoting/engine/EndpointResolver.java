package org.jenkinsci.remoting.engine;

import hudson.remoting.Base64;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

public abstract class EndpointResolver {

    public abstract JnlpAgentEndpoint resolve() throws IOException;

    public abstract void waitForReady() throws InterruptedException;

    static RSAPublicKey getIdentity(String base64EncodedIdentity) throws InvalidKeySpecException {
        if (base64EncodedIdentity == null) return null;
        try {
            byte[] encodedKey = Base64.decode(base64EncodedIdentity);
            if (encodedKey == null) return null;
            X509EncodedKeySpec spec = new X509EncodedKeySpec(encodedKey);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) kf.generatePublic(spec);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The Java Language Specification mandates RSA as a supported algorithm.", e);
        }
    }

}
