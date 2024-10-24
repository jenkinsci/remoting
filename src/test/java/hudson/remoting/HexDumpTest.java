package hudson.remoting;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * @author Kohsuke Kawaguchi
 */
public class HexDumpTest {
    @Test
    public void test1() {
        assertEquals("0x00 0x01 0xff 'A'", HexDump.toHex(new byte[] {0, 1, -1, 65}));
        assertEquals("0x00 0x01 0xff 'ABC'", HexDump.toHex(new byte[] {0, 1, -1, 65, 66, 67}));
        assertEquals("'AAAA' 0x00", HexDump.toHex(new byte[] {65, 65, 65, 65, 0}));
    }

    @Test
    public void testMultiline() {
        assertEquals("'A A' 0x0a\n' AA'", HexDump.toHex(new byte[] {65, 32, 65, 10, 32, 65, 65}));
    }
}
