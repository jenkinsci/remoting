package hudson.remoting;

import junit.framework.TestCase;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * @author Kohsuke Kawaguchi
 */
public class ExportTableTest extends TestCase {
    public void testDiagnosis() throws Exception {
        ExportTable<Object> e = new ExportTable<Object>();

        int i = e.export("foo");
        assertEquals("foo", e.get(i));

        e.unexportByOid(i);
        try {
            e.get(i);
            fail();
        } catch (IllegalStateException x) {
            StringWriter sw = new StringWriter();
            x.printStackTrace(new PrintWriter(sw));
            assertTrue(sw.toString().contains("Object was recently deallocated"));
            assertTrue(sw.toString().contains("ExportTable.export"));
        }
    }
}
