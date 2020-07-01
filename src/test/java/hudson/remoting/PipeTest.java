/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting;

import junit.framework.Test;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.For;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

/**
 * Test {@link Pipe}.
 *
 * @author Kohsuke Kawaguchi
 */
public class PipeTest extends RmiTestBase implements Serializable {
    /**
     * Test the "remote-write local-read" pipe.
     */
    public void testRemoteWrite() throws Exception {
        Pipe p = Pipe.createRemoteToLocal();
        Future<Integer> f = channel.callAsync(new WritingCallable(p));

        read(p);

        int r = f.get();
        System.out.println("result=" + r);
        assertEquals(5,r);
    }
    
    /**
     * Have the reader close the read end of the pipe while the writer is still writing.
     * The writer should pick up a failure.
     */
    @Issue("JENKINS-8592")
    @For(Pipe.class)
    public void testReaderCloseWhileWriterIsStillWriting() throws Exception {
        final Pipe p = Pipe.createRemoteToLocal();
        final Future<Void> f = channel.callAsync(new InfiniteWriter(p));
        final InputStream in = p.getIn();
        assertEquals(in.read(), 0);
        in.close();

        try {
            f.get();
            fail();
        } catch (ExecutionException e) {
            // should have resulted in an IOException
            if (!(e.getCause() instanceof IOException)) {
                e.printStackTrace();
                fail();
            }
        }
    }

    /**
     * Just writes forever to the pipe
     */
    private static class InfiniteWriter extends CallableBase<Void, Exception> {
        private final Pipe pipe;

        public InfiniteWriter(Pipe pipe) {
            this.pipe = pipe;
        }

        @Override
        public Void call() throws Exception {
            while (true) {
                pipe.getOut().write(0);
                Thread.sleep(10);
            }
        }
        private static final long serialVersionUID = 1L;
    }

    private static class WritingCallable extends CallableBase<Integer, IOException> {
        private final Pipe pipe;

        public WritingCallable(Pipe pipe) {
            this.pipe = pipe;
        }

        @Override
        public Integer call() throws IOException {
            write(pipe);
            return 5;
        }
        private static final long serialVersionUID = 1L;
    }

    /**
     * Test the "local-write remote-read" pipe.
     */
    public void testLocalWrite() throws Exception {
        Pipe p = Pipe.createLocalToRemote();
        Future<Integer> f = channel.callAsync(new ReadingCallable(p));

        write(p);

        int r = f.get();
        System.out.println("result=" + r);
        assertEquals(5,r);
    }

    public void testLocalWrite2() throws Exception {
        Pipe p = Pipe.createLocalToRemote();
        Future<Integer> f = channel.callAsync(new ReadingCallable(p));

        Thread.sleep(2000); // wait for remote to connect to local.
        write(p);

        int r = f.get();
        System.out.println("result=" + r);
        assertEquals(5,r);
    }

    public interface ISaturationTest {
        void ensureConnected();
        int readFirst() throws IOException;
        void readRest() throws IOException;
    }

    public void testSaturation() throws Exception {
        if (channelRunner instanceof InProcessCompatibilityRunner)
            return; // can't do this test without the throttling support.

        final Pipe p = Pipe.createLocalToRemote();

        Thread writer = new Thread() {
            final Thread mainThread = Thread.currentThread(); // this makes it easy to see the relationship between the thread pair in the debugger
            @Override
            public void run() {
                OutputStream os = p.getOut();
                try {
                    byte[] buf = new byte[Channel.PIPE_WINDOW_SIZE*2+1];
                    os.write(buf);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };

        // 1. wait until the receiver sees the first byte. at this point the pipe should be completely clogged
        // 2. make sure the writer thread is still alive, blocking
        // 3. read the rest

        ISaturationTest target = channel.call(new CreateSaturationTestProxy(p));

        // make sure the pipe is connected
        target.ensureConnected();
        channel.syncLocalIO();
        // then let the writer commence
        writer.start();

        // make sure that some data arrived to the receiver
        // at this point the pipe should be fully clogged
        assertEquals(0,target.readFirst());

        // the writer should be still blocked
        Thread.sleep(1000);
        assertTrue(writer.isAlive());

        target.readRest();
    }

    private static class CreateSaturationTestProxy extends CallableBase<ISaturationTest,IOException> {
        private final Pipe pipe;

        public CreateSaturationTestProxy(Pipe pipe) {
            this.pipe = pipe;
        }

        @Override
        public ISaturationTest call() {
            return Channel.currentOrFail().export(ISaturationTest.class, new ISaturationTest() {
                private InputStream in;
                @Override
                public void ensureConnected() {
                    in = pipe.getIn();
                }

                @Override
                public int readFirst() throws IOException {
                    return in.read();
                }

                @Override
                public void readRest() throws IOException {
                    new DataInputStream(in).readFully(new byte[Channel.PIPE_WINDOW_SIZE*2]);
                }
            });
        }
        private static final long serialVersionUID = 1L;
    }

    private static class ReadingCallable extends CallableBase<Integer, IOException> {
        private final Pipe pipe;

        public ReadingCallable(Pipe pipe) {
            this.pipe = pipe;
        }

        @Override
        public Integer call() throws IOException {
            try {
                read(pipe);
            } catch(AssertionError ex) {
                // Propagate the assetion to the remote side
                throw new IOException("Assertion failed", ex);
            }
            return 5;
        }
        private static final long serialVersionUID = 1L;
    }

    private static void write(Pipe pipe) throws IOException {
        OutputStream os = pipe.getOut();
        byte[] buf = new byte[384];
        for( int i=0; i<256; i++ ) {
            Arrays.fill(buf,(byte)i);
            os.write(buf,0,256);
        }
        os.close();
    }

    private static void read(Pipe p) throws IOException, AssertionError {
        try (InputStream in = p.getIn()) {
            for( int cnt=0; cnt<256*256; cnt++ ) {
                assertEquals(cnt/256,in.read());
            }
            assertEquals(-1,in.read());
        }
    }


    public void _testSendBigStuff() throws Exception {
        OutputStream f = channel.call(new DevNullSink());

        for (int i=0; i<1024*1024; i++)
            f.write(new byte[8000]);
        f.close();
    }

    /**
     * Writer end closes even before the remote computation kicks in.
     */
    public void testQuickBurstWrite() throws Exception {
        final Pipe p = Pipe.createLocalToRemote();
        Future<Integer> f = channel.callAsync(new QuickBurstCallable(p));
        OutputStream os = p.getOut();
        os.write(1);
        os.close();

        // at this point the async executable kicks in.
        // TODO: introduce a lock to ensure the ordering.

        assertEquals(1,(int)f.get());
    }

    private static class DevNullSink extends CallableBase<OutputStream, IOException> {
        @Override
        public OutputStream call() {
            return new RemoteOutputStream(new NullOutputStream());
        }
        private static final long serialVersionUID = 1L;
    }

    public static Test suite() {
        return buildSuite(PipeTest.class);
    }

    private Object writeReplace() {
        return null;
    }

    private static class QuickBurstCallable extends CallableBase<Integer, IOException> {
        private final Pipe p;

        public QuickBurstCallable(Pipe p) {
            this.p = p;
        }

        @Override
        public Integer call() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            IOUtils.copy(p.getIn(), baos);
            return baos.size();
        }
        private static final long serialVersionUID = 1L;
    }
    private static final long serialVersionUID = 1L;
}
