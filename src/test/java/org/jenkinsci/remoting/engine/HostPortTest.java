package org.jenkinsci.remoting.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HostPortTest {

    @Test
    void testHostname() {
        HostPort hostPort = new HostPort("hostname:5555");
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    void testFqdn() {
        HostPort hostPort = new HostPort("hostname.example.com:5555");
        assertThat(hostPort.getHost(), is("hostname.example.com"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    void testIPv4() {
        HostPort hostPort = new HostPort("1.2.3.4:5555");
        assertThat(hostPort.getHost(), is("1.2.3.4"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    void testHostWhitespace() {
        HostPort hostPort = new HostPort("  1.2.3.4  :5555");
        assertThat(hostPort.getHost(), is("1.2.3.4"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    void testPortWhitespace() {
        HostPort hostPort = new HostPort("1.2.3.4:   5555  ");
        assertThat(hostPort.getHost(), is("1.2.3.4"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    void testIPv6() {
        HostPort hostPort = new HostPort("[1:2::3:4]:5555");
        assertThat(hostPort.getHost(), is("1:2::3:4"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    void testIPv6Whitespace() {
        HostPort hostPort = new HostPort("[  1:2::3:4  ]:5555");
        assertThat(hostPort.getHost(), is("1:2::3:4"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    void testIPv6NoPort() {
        assertThrows(IllegalArgumentException.class, () -> new HostPort("[1:2::3:4]"));
    }

    @Test
    void testUnclosedIPv6() {
        assertThrows(IllegalArgumentException.class, () -> new HostPort("[1:2::3:4:5555"));
    }

    @Test
    void testInvalidPort() {
        assertThrows(IllegalArgumentException.class, () -> new HostPort("[1:2::3:4]:host"));
    }

    @Test
    void testNoSeparator() {
        assertThrows(IllegalArgumentException.class, () -> new HostPort("hostname"));
    }

    @Test
    void testNoHost() {
        HostPort hostPort = new HostPort(":5555", "hostname", -1);
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    void testEmptyHost() {
        HostPort hostPort = new HostPort("    :5555", "hostname", -1);
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    void testEmptyPort() {
        HostPort hostPort = new HostPort("hostname:   ", null, 7777);
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(7777));
    }

    @Test
    void testSeparatorNoPort() {
        HostPort hostPort = new HostPort("hostname:", null, 7777);
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(7777));
    }

    @Test
    void testNegativePort() {
        assertThrows(IllegalArgumentException.class, () -> new HostPort("hostname:-4"));
    }

    @Test
    void testPortTooHigh() {
        assertThrows(IllegalArgumentException.class, () -> new HostPort("hostname:100000"));
    }

    @Test
    void testOnlySeparator() {
        HostPort hostPort = new HostPort(":", "hostname", 7777);
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(7777));
    }

    @Test
    void testPortNotANumber() {
        assertThrows(NumberFormatException.class, () -> new HostPort("hostname:notAPort"));
    }
}
