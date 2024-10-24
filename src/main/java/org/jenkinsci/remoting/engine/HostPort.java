package org.jenkinsci.remoting.engine;

class HostPort {

    private static final int PORT_MIN = 0;
    private static final int PORT_MAX = (1 << 16) - 1;

    private String host;
    private int port;

    public HostPort(String value) {
        splitHostPort(value, null, 0);
    }

    public HostPort(String value, String defaultHost, int defaultPort) {
        splitHostPort(value, defaultHost, defaultPort);
    }

    private void splitHostPort(String value, String defaultHost, int defaultPort) {
        String hostPortValue = value.trim();
        if (hostPortValue.charAt(0) == '[') {
            extractIPv6(hostPortValue);
            return;
        }
        int portSeparator = hostPortValue.lastIndexOf(':');
        if (portSeparator < 0) {
            throw new IllegalArgumentException("Invalid HOST:PORT value: " + value);
        }
        String hostValue = hostPortValue.substring(0, portSeparator).trim();
        host = hostValue.length() > 0 ? hostValue : defaultHost;
        String portString = hostPortValue.substring(portSeparator + 1).trim();
        if (portString.length() > 0) {
            port = Integer.parseInt(portString);
            if (port <= PORT_MIN || port > PORT_MAX) {
                throw new IllegalArgumentException(
                        "Port " + value + " out of valid range [" + PORT_MIN + ", " + PORT_MAX + ")");
            }
        } else {
            port = defaultPort;
        }
    }

    private void extractIPv6(String hostPortValue) {
        int endBracket = hostPortValue.indexOf(']');
        if (endBracket < 2) {
            throw new IllegalArgumentException("Invalid IPv6 value.");
        }
        host = hostPortValue.substring(1, endBracket).trim();
        int portSeparator = hostPortValue.lastIndexOf(':');
        if (portSeparator < endBracket) {
            throw new IllegalArgumentException("Missing port.");
        }
        port = Integer.parseInt(hostPortValue.substring(portSeparator + 1).trim());
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
