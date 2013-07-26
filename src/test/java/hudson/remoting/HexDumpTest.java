package hudson.remoting;

import junit.framework.TestCase;

/**
 * @author Kohsuke Kawaguchi
 */
public class HexDumpTest extends TestCase {
    public  void test1() {
        assertEquals("0x00 0x01 0xff 'A'", HexDump.toHex(new byte[] {0, 1, -1, 65}));
    }
}
