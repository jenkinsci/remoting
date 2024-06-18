package hudson.remoting;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NoProxyEvaluatorTest {

    @Test
    public void testWrongIPV4() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("127.0.0.1");

        assertTrue(evaluator.shouldProxyHost("10.0.0.1"));
    }

    @Test
    public void testIPV6() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("2001:0db8:85a3:0000:0000:8a2e:0370:7334");
        assertFalse(evaluator.shouldProxyHost("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
    }

    @Test
    public void testWrongIPV6() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("0:0:0:0:0:0:0:1");

        assertTrue(evaluator.shouldProxyHost("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
    }

    @Test
    public void testFQDN() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("foobar.com");

        assertFalse(evaluator.shouldProxyHost("foobar.com"));
        assertFalse(evaluator.shouldProxyHost("sub.foobar.com"));
        assertFalse(evaluator.shouldProxyHost("sub.sub.foobar.com"));

        assertTrue(evaluator.shouldProxyHost("foobar.org"));
        assertTrue(evaluator.shouldProxyHost("jenkins.com"));
    }

    @Test
    public void testSubFQDN() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("sub.foobar.com");

        assertFalse(evaluator.shouldProxyHost("sub.foobar.com"));
        assertFalse(evaluator.shouldProxyHost("sub.sub.foobar.com"));

        assertTrue(evaluator.shouldProxyHost("foobar.com"));
    }

    @Test
    public void testFQDNWithDot() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator(".foobar.com");

        assertFalse(evaluator.shouldProxyHost("foobar.com"));
        assertFalse(evaluator.shouldProxyHost("sub.foobar.com"));
        assertFalse(evaluator.shouldProxyHost("sub.sub.foobar.com"));

        assertTrue(evaluator.shouldProxyHost("foobar.org"));
        assertTrue(evaluator.shouldProxyHost("jenkins.com"));
    }

    @Test
    public void testSubFQDNWithDot() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator(".sub.foobar.com");

        assertFalse(evaluator.shouldProxyHost("sub.foobar.com"));
        assertFalse(evaluator.shouldProxyHost("sub.sub.foobar.com"));

        assertTrue(evaluator.shouldProxyHost("foobar.com"));
    }

    @Test
    public void testSubFWDNWithDotMinimalSuffix() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator(".svc");

        assertFalse(evaluator.shouldProxyHost("bn-myproj.svc"));
    }

    @Test
    public void testSubFWDNWithDotMinimalSuffixMixedCase() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator(
                ".svc,.default,.local,localhost,.boehringer.com,10.250.0.0/16,10.251.0.0/16,10.183.195.106,10.183.195.107,10.183.195.108,10.183.195.109,10.183.195.11,10.183.195.111,10.183.195.112,10.183.195.113,10.183.195.13,10.250.127.");

        assertFalse(evaluator.shouldProxyHost("bn-myproj.svc"));
    }

    @Test
    public void testWithInvalidCharsMatching() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("foo+.co=m");

        assertFalse(evaluator.shouldProxyHost("foo+.co=m"));
    }

    @Test
    public void testWithInvalidCharsNonMatching() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("foo+.co=m");

        assertTrue(evaluator.shouldProxyHost("foo.com"));
    }

    @Test
    public void testMixed() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator(" 127.0.0.1,  0:0:0:0:0:0:0:1,\tfoobar.com, .jenkins.com");

        assertFalse(evaluator.shouldProxyHost("127.0.0.1"));
        assertFalse(evaluator.shouldProxyHost("0:0:0:0:0:0:0:1"));
        assertFalse(evaluator.shouldProxyHost("foobar.com"));
        assertFalse(evaluator.shouldProxyHost("sub.foobar.com"));
        assertFalse(evaluator.shouldProxyHost("sub.jenkins.com"));

        assertTrue(evaluator.shouldProxyHost("foobar.org"));
        assertTrue(evaluator.shouldProxyHost("jenkins.org"));
        assertTrue(evaluator.shouldProxyHost("sub.foobar.org"));
        assertTrue(evaluator.shouldProxyHost("sub.jenkins.org"));
    }

    @Test
    public void testSimpleHostname() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("jenkinsmaster");

        assertFalse(evaluator.shouldProxyHost("jenkinsmaster"));
    }

    @Test
    public void testIPv4Loopback() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator(null);

        assertFalse(evaluator.shouldProxyHost("127.0.0.1"));
    }

    @Test
    public void testIPv6LoopbackAbbreviated() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator(null);

        assertFalse(evaluator.shouldProxyHost("::1"));
    }

    @Test
    public void testIPv6LoopbackFullLength() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator(null);

        assertFalse(evaluator.shouldProxyHost("0000:0000:0000:0000:0000:0000:0000:0001"));
    }

    @Test
    public void testLocalhost() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator(null);

        assertFalse(evaluator.shouldProxyHost("localhost"));
    }

    @Test
    public void testNullSpecification() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator(null);

        assertTrue(evaluator.shouldProxyHost("jenkins.io"));
    }

    @Test
    public void testEmptySpecification() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("");

        assertTrue(evaluator.shouldProxyHost("jenkins.io"));
    }

    @Test
    public void testBlankSpecification() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("    ");

        assertTrue(evaluator.shouldProxyHost("jenkins.io"));
    }

    @Test(expected = NullPointerException.class)
    public void testNullHost() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("jenkins.io");

        assertTrue(evaluator.shouldProxyHost(null));
    }

    @Test
    public void testEmptyHost() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("jenkins.io");

        assertTrue(evaluator.shouldProxyHost(""));
    }

    @Test
    public void testBlankHost() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("jenkins.io");

        assertTrue(evaluator.shouldProxyHost("     "));
    }

    @Test
    public void testCidrWithin() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("1.232.12.0/20");

        assertFalse(evaluator.shouldProxyHost("1.232.12.3"));
    }

    @Test
    public void testCidrBelow() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("1.232.12.0/20");

        assertFalse(evaluator.shouldProxyHost("1.232.11.3"));
    }

    @Test
    public void testCidrAbove() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("1.232.12.0/24");

        assertTrue(evaluator.shouldProxyHost("1.232.13.3"));
    }

    @Test
    public void testJavaStyleSeparator() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("foobar.com| .jenkins.io");

        assertFalse(evaluator.shouldProxyHost("sub.foobar.com"));
        assertFalse(evaluator.shouldProxyHost("repo.jenkins.io"));
        assertTrue(evaluator.shouldProxyHost("foo.com"));
    }

    @Test
    public void testMixedSeparators() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("foobar.com| .jenkins.io,fred.com");

        assertFalse(evaluator.shouldProxyHost("sub.fred.com"));
        assertTrue(evaluator.shouldProxyHost("sub.foobar.com"));
        assertTrue(evaluator.shouldProxyHost("repo.jenkins.io"));
        assertTrue(evaluator.shouldProxyHost("foo.com"));
    }

    @Test
    public void testIPv6Full() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("2001:0db8:0a0b:12f0:0000:0000:0000:0001");

        assertFalse(evaluator.shouldProxyHost("2001:0db8:0a0b:12f0:0000:0000:0000:0001"));
        assertFalse(evaluator.shouldProxyHost("2001:0db8:0a0b:12f0::0001"));
    }

    @Test
    public void testIPv6Compressed() {
        NoProxyEvaluator evaluator = new NoProxyEvaluator("2001:0db8:0a0b:12f0::0001");

        assertFalse(evaluator.shouldProxyHost("2001:0db8:0a0b:12f0:0000:0000:0000:0001"));
        assertFalse(evaluator.shouldProxyHost("2001:0db8:0a0b:12f0::0001"));
    }
}
