package org.jenkinsci.remoting.nio;

import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * @author Kohsuke Kawaguchi
 */
public class Main {
    /*
       Non-blocking stream copier
       --------------------------

       Allow arbitrary pair of InputStream+OutputStream and pump them all just by using a single thread.

       InputStream needs to be SelectableChannel or FileInputStream on Linux.
       Now OutputStream, this is a bigger problem!
           - SelectableChannel, such as another FileOutputStream or Socket is fine
           - ProxyOutputStream, to send bits remotely. But this involves window support.
             -> if ProxyOutputStream can't write, set the bytes aside and don't look for OP_READ
                in corresponding reader.
             -> when window size becomes available, notify and act

             -> but ProxyOutputStream is still a blocking write.


      Pumping executors forking builds aren't too interesting. But ChannelReaderThread can
      benefit from this.

      Need an additional framing mechanism

    */

    public static void main(String[] args) throws Exception {
        testProcessSelection();
    }

    /**
     * Tests NIO with pipe to child processes.
     */
    private static void testProcessSelection() throws Exception {
        SelectableFileChannelFactory f = new SelectableFileChannelFactory();
        Selector sel = Selector.open();

        {
            SocketChannel c1 = f.create(unwrap(open("tail", "-f", "/tmp/test")));
            c1.configureBlocking(false);
            c1.register(sel, SelectionKey.OP_READ).attach("tail -f /tmp/test1");

            SocketChannel c2 = f.create(unwrap(open("tail", "-f", "/tmp/test2")));
            c2.configureBlocking(false);
            c2.register(sel, SelectionKey.OP_READ).attach("tail -f /tmp/test2");
        }

        while (true) {
            sel.select();
            for (SelectionKey sk : sel.selectedKeys()) {
                System.out.println("==== " + sk.attachment());
                SocketChannel c = (SocketChannel) sk.channel();

                ByteBuffer buf = ByteBuffer.allocate(1024);
                c.read(buf);
                System.out.write(buf.array(), 0, buf.position());
            }
            sel.selectedKeys().clear();
        }
    }

    private static FileInputStream unwrap(InputStream i) throws Exception {
        while (true) {
            if (i instanceof FilterInputStream) {
                Field $in = FilterInputStream.class.getDeclaredField("in");
                $in.setAccessible(true);
                i = (InputStream) $in.get(i);
                continue;
            }
            if (i instanceof FileInputStream) {
                return (FileInputStream) i;
            }
            return null; // unknown type
        }
    }

    private static InputStream open(String... args) throws IOException {
        Process p = new ProcessBuilder(args).start();
        p.getOutputStream().close();
        p.getErrorStream().close();

        return p.getInputStream();
    }
}
