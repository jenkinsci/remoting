package org.jenkinsci.remoting.engine;

class HostPort {
    private String host;
    private int port;

    public HostPort(String value) {
        splitHostPort(value);
    }

    private void splitHostPort(String value) {
        int portSeparator = value.lastIndexOf(':');
        if (portSeparator <= 0) {
            throw new IllegalArgumentException("Invalid HOST:PORT value: " + value);
        }
        host = value.substring(0, portSeparator);
        if (host.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid HOST:PORT value: " + value);
        }
        port = Integer.parseInt(value.substring(portSeparator + 1));
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
