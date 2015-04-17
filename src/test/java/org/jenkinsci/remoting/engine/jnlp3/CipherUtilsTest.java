package org.jenkinsci.remoting.engine.jnlp3;

import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * Tests for {@link CipherUtils}.
 *
 * @author Akshay Dayal
 */
public class CipherUtilsTest {

    @Test
    public void testGenerate128BitKey() {
        byte[] firstKey = CipherUtils.generate128BitKey();
        assertEquals(16, firstKey.length);
        byte[] secondKey = CipherUtils.generate128BitKey();
        assertEquals(16, secondKey.length);
        assertThat(firstKey, IsNot.not(IsEqual.equalTo(secondKey)));
    }

    @Test
    public void testKeyToString() throws Exception {
        byte[] key = "This is a string".getBytes();
        assertEquals(new String(key, "ISO-8859-1"), CipherUtils.keyToString(key));
    }

    @Test
    public void testKeyFromString() throws Exception {
        byte[] key = "This is a string".getBytes();
        assertArrayEquals(key, CipherUtils.keyFromString(new String(key, "ISO-8859-1")));
    }
}
