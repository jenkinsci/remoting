package hudson.remoting;

import hudson.remoting.util.OneShotEvent;
import org.jvnet.hudson.test.Bug;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author Kohsuke Kawaguchi
 */
public class Bug20707Test extends RmiTestBase implements Serializable {
    @Bug(20707)
    public void testGc() throws Exception {
        final DummyClassLoader cl = new DummyClassLoader(TestEcho.class);
        final IEcho c = cl.load(TestEcho.class);
        c.set(new Object[]{
                new HangInducer(),
                cl.load(TestEcho.class) // <- DA BOMB. We are trying to blow up the deserialization of this
        });

        // when the response comes back, make it hang
        HANG = new OneShotEvent();

        ExecutorService es = Executors.newCachedThreadPool();

        final java.util.concurrent.Future<Object> f = es.submit(new java.util.concurrent.Callable<Object>() {
            public Object call() throws Exception {
                // send a couple of objects, which exports DummyClassLoader.
                // when the computation is done on the other side, RemoteClassLoader can be garbage collected any time
                // on this side, the obtained UserResponse gets passed to the thread that made the request
                // (this thread)

                channel.call(new TestEcho(c));

                // we'll use HangInducer.HANG to make the response unmarshalling hang

                return null;
            }
        });

        // wait until the echo call comes back and hangs at the unmarshalling
        BLOCKING.block();

        // induce GC on the other side until we get classloader unexported
        channel.call(new Callable<Void,InterruptedException>() {
            public Void call() throws InterruptedException {
                while (ECHO_CLASSLOADER.get()!=null) {
                    System.gc();
                    Thread.sleep(100);
                }
                return null;
            }
        });

        // by the time the above Callable comes back, Unexport command has executed and classloader is unexported
        assertFalse(channel.exportedObjects.isExported(cl));

        // and now if we let the unmarshalling go, it'll finish deserializing HangInducer
        // and will try to unmarshal DA BOMB, and it should blow up
        f.get();
    }

    private Object writeReplace() {
        return null;
    }

    public static interface IEcho extends Callable<Object,IOException> {
        void set(Object o);
        Object get();
    }

    public static class HangInducer implements Serializable {
        private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            if (Channel.current().getName().equals("north")) {
                try {
                    BLOCKING.signal();  // let the world know that we are hanging now
                    HANG.block();
                } catch (InterruptedException e) {
                    throw new Error(e);
                }
            }
        }

        private static final long serialVersionUID = 1L;
    }

    private static OneShotEvent BLOCKING = new OneShotEvent();
    private static OneShotEvent HANG;

    static WeakReference<ClassLoader> ECHO_CLASSLOADER;

    public static void set(WeakReference<ClassLoader> cl) {
        ECHO_CLASSLOADER = cl;
    }
}
