package org.jenkinsci.remoting.engine;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.EngineListenerSplitter;
import hudson.remoting.JarCache;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;

public abstract class AbstractEndpointConnector implements EndpointConnector {
    @NonNull
    protected final String agentName;

    @NonNull
    protected final String secretKey;

    @NonNull
    protected final ExecutorService executor;

    @NonNull
    protected final EngineListenerSplitter events;

    @NonNull
    protected final Duration noReconnectAfter;

    @CheckForNull
    protected final List<X509Certificate> candidateCertificates;

    protected final boolean disableHttpsCertValidation;

    @CheckForNull
    protected final JarCache jarCache;

    @CheckForNull
    protected final String proxyCredentials;

    public AbstractEndpointConnector(
            @NonNull String agentName,
            @NonNull String secretKey,
            @NonNull ExecutorService executor,
            @NonNull EngineListenerSplitter events,
            @NonNull Duration noReconnectAfter,
            @CheckForNull List<X509Certificate> candidateCertificates,
            boolean disableHttpsCertValidation,
            @CheckForNull JarCache jarCache,
            @CheckForNull String proxyCredentials) {
        this.agentName = agentName;
        this.secretKey = secretKey;
        this.executor = executor;
        this.events = events;
        this.noReconnectAfter = noReconnectAfter;
        this.candidateCertificates = candidateCertificates;
        this.disableHttpsCertValidation = disableHttpsCertValidation;
        this.jarCache = jarCache;
        this.proxyCredentials = proxyCredentials;
    }
}
