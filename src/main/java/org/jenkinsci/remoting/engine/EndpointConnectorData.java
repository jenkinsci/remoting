package org.jenkinsci.remoting.engine;

import hudson.remoting.EngineListenerSplitter;
import hudson.remoting.JarCache;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Captures the data needed to connect to any endpoint.
 * @param agentName the agent name
 * @param secretKey the secret key
 * @param executor the thread pool to use for handling TCP connections
 * @param events the listener to log to
 * @param noReconnectAfter Specifies the duration after which the connection should not be re-established.
 * @param candidateCertificates the list of certificates to be used for the connection
 * @param disableHttpsCertValidation whether to disable HTTPS certificate validation
 * @param jarCache Where to store the jar cache
 * @param proxyCredentials Credentials to use for proxy authentication, if any.
 * @since TODO
 */
public record EndpointConnectorData(
        String agentName,
        String secretKey,
        ExecutorService executor,
        EngineListenerSplitter events,
        Duration noReconnectAfter,
        List<X509Certificate> candidateCertificates,
        boolean disableHttpsCertValidation,
        JarCache jarCache,
        String proxyCredentials) {}
