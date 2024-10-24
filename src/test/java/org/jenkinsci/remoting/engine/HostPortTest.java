package org.jenkinsci.remoting.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.Test;

public class HostPortTest {

    @Test
    public void testHostname() {
        HostPort hostPort = new HostPort("hostname:5555");
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    public void testFqdn() {
        HostPort hostPort = new HostPort("hostname.example.com:5555");
        assertThat(hostPort.getHost(), is("hostname.example.com"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    public void testIPv4() {
        HostPort hostPort = new HostPort("1.2.3.4:5555");
        assertThat(hostPort.getHost(), is("1.2.3.4"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    public void testHostWhitespace() {
        HostPort hostPort = new HostPort("  1.2.3.4  :5555");
        assertThat(hostPort.getHost(), is("1.2.3.4"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    public void testPortWhitespace() {
        HostPort hostPort = new HostPort("1.2.3.4:   5555  ");
        assertThat(hostPort.getHost(), is("1.2.3.4"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    public void testIPv6() {
        HostPort hostPort = new HostPort("[1:2::3:4]:5555");
        assertThat(hostPort.getHost(), is("1:2::3:4"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    public void testIPv6Whitespace() {
        HostPort hostPort = new HostPort("[  1:2::3:4  ]:5555");
        assertThat(hostPort.getHost(), is("1:2::3:4"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIPv6NoPort() {
        new HostPort("[1:2::3:4]");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnclosedIPv6() {
        new HostPort("[1:2::3:4:5555");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidPort() {
        new HostPort("[1:2::3:4]:host");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNoSeparator() {
        new HostPort("hostname");
    }

    @Test
    public void testNoHost() {
        HostPort hostPort = new HostPort(":5555", "hostname", -1);
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    public void testEmptyHost() {
        HostPort hostPort = new HostPort("    :5555", "hostname", -1);
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(5555));
    }

    @Test
    public void testEmptyPort() {
        HostPort hostPort = new HostPort("hostname:   ", null, 7777);
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(7777));
    }

    @Test
    public void testSeparatorNoPort() {
        HostPort hostPort = new HostPort("hostname:", null, 7777);
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(7777));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNegativePort() {
        new HostPort("hostname:-4");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPortTooHigh() {
        new HostPort("hostname:100000");
    }

    @Test
    public void testOnlySeparator() {
        HostPort hostPort = new HostPort(":", "hostname", 7777);
        assertThat(hostPort.getHost(), is("hostname"));
        assertThat(hostPort.getPort(), is(7777));
    }

    @Test(expected = NumberFormatException.class)
    public void testPortNotANumber() {
        new HostPort("hostname:notAPort");
    }
}
