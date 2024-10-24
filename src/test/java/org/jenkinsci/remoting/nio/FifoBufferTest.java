package org.jenkinsci.remoting.nio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author Kohsuke Kawaguchi
 */
public class FifoBufferTest extends Assert {
    FifoBuffer buf = new FifoBuffer(8, 256);

    @Test
    public void readWrite() throws Exception {
        buf.write(b(TEN));
        buf.close();
        assertEquals(10, buf.readable());

        byte[] b = new byte[16];
        int r = buf.read(b);
        assertEquals(r, 10);
        assertEquals(new String(b, 0, r), TEN);

        assertEquals(-1, buf.readable());
    }

    @Test
    public void nio() throws Exception {
        buf.write(b(TEN));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        WritableByteChannel ch = Channels.newChannel(baos);

        // read via send method, and we get them all back
        int r = buf.send(ch);
        assertEquals(10, r);
        assertEquals(TEN, baos.toString());

        // there's no more to read
        assertEquals(0, buf.readable());
        assertEquals(0, buf.send(ch));

        // if we close, the value changes to -1 to indicate EOF
        buf.close();
        assertEquals(-1, buf.readable());
        assertEquals(-1, buf.send(ch));
    }

    @Test
    public void nonBlockingWrite() throws Exception {
        buf.setLimit(185);

        for (int i = 0; i < 18; i++) {
            assertEquals(10, buf.writeNonBlock(bb(TEN)));
        }
        assertEquals(5, buf.writeNonBlock(bb(TEN)));

        // can't write any more
        assertEquals(0, buf.writeNonBlock(bb(TEN)));
        assertEquals(185, buf.readable());

        // if we make some space, we can write some more again
        byte[] b = new byte[5];
        buf.read(b);
        assertEquals(FIVE, new String(b));

        assertEquals(5, buf.writeNonBlock(bb(TEN)));
    }

    @Test
    public void receive() throws Exception {
        for (int i = 0; i < 25; i++) {
            assertEquals(10, buf.receive(Channels.newChannel(bs(TEN))));
        }

        // the last one will only read 6 bytes
        ReadableByteChannel ch = Channels.newChannel(bs(TEN));
        assertEquals(6, buf.receive(ch));
        assertEquals(256, buf.readable());

        // we should be able to read 4 more bytes from  'ch' since buf.receive() shouldn't have read it
        byte[] d = new byte[10];
        assertEquals(4, ch.read(ByteBuffer.wrap(d)));

        assertEquals("6789", new String(d, 0, 4));
    }

    @Test
    public void peek() throws Exception {
        for (int i = 0; i < 4; i++) {
            buf.write(b(TEN));
        }

        for (int i = 0; i < 4; i++) {
            byte[] d = new byte[10];
            assertEquals(10, buf.peek(i * 10, d));
            assertEquals(TEN, new String(d));
        }

        // peek toward the end
        byte[] d = new byte[10];
        assertEquals(4, buf.peek(36, d));
        assertEquals("6789", new String(d, 0, 4));
    }

    private InputStream bs(String s) {
        return new ByteArrayInputStream(b(s));
    }

    private byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private ByteBuffer bb(String s) {
        return ByteBuffer.wrap(b(s));
    }

    static final String FIVE = "01234";

    /**
     * Ten bytes of data
     */
    static final String TEN = "0123456789";
}
