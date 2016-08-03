package org.jenkinsci.remoting.engine;

/**
 * @author Stephen Connolly
 */
public interface JnlpClientDatabase {

    boolean exists(String clientName);

    String getSecretOf(String clientName);
}
