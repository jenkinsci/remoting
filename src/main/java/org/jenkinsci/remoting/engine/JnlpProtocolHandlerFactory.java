package org.jenkinsci.remoting.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import javax.net.ssl.SSLContext;
import org.jenkinsci.remoting.nio.NioChannelHub;
import org.jenkinsci.remoting.protocol.IOHub;

public class JnlpProtocolHandlerFactory {
    private NioChannelHub nioChannelHub;
    private IOHub ioHub;
    private SSLContext context;
    private JnlpProtocol3Handler.SecretDatabase secretDatabase;
    private final ExecutorService threadPool;

    public JnlpProtocolHandlerFactory(ExecutorService threadPool) {
        this.threadPool = threadPool;
    }

    public JnlpProtocolHandlerFactory withNioChannelHub(NioChannelHub nioChannelHub) {
        this.nioChannelHub = nioChannelHub;
        return this;
    }

    public JnlpProtocolHandlerFactory withIOHub(IOHub ioHub) {
        this.ioHub = ioHub;
        return this;
    }

    public JnlpProtocolHandlerFactory withSSLContext(SSLContext context) {
        this.context = context;
        return this;
    }

    public JnlpProtocolHandlerFactory withSecretDatabase(JnlpProtocol3Handler.SecretDatabase secretDatabase) {
        this.secretDatabase = secretDatabase;
        return this;
    }

    public List<JnlpProtocolHandler> handlers() {
        List<JnlpProtocolHandler> result = new ArrayList<JnlpProtocolHandler>();
        if (ioHub != null && context != null) {
            result.add(new JnlpProtocol4Handler(threadPool, ioHub, context));
        }
        result.add(new JnlpProtocol3Handler(threadPool, nioChannelHub, secretDatabase));
        result.add(new JnlpProtocol2Handler(threadPool, nioChannelHub));
        result.add(new JnlpProtocol1Handler(threadPool, nioChannelHub));
        return result;
    }
}
