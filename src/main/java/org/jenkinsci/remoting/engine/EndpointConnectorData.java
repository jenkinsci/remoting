package org.jenkinsci.remoting.engine;

import hudson.remoting.EngineListenerSplitter;
import hudson.remoting.JarCache;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

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
