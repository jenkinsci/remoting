package hudson.remoting;

import java.io.Serializable;

public class RemoteInvocationHandlerTest extends RmiTestBase {

    public void testMethodSelection() throws Exception {
        final Impl i = new Impl();
        channel.call(new Task(i));
        assertEquals("value", i.arg);
    }

    public void testExportPrimary() throws Exception {
        final Impl i = new Impl();
        Contract2 c2 = channel.export(Contract2.class, i);
        Contract c = channel.export(Contract.class, i);
        channel.call(new Task2(c2));
        assertEquals("value", i.arg);
    }

    public void testExportSecondary() throws Exception {
        final Impl i = new Impl();
        Contract c1 = channel.export(Contract.class, i);
        Contract2 c2 = channel.export(Contract2.class, i);
        channel.call(new Task2(c2));
        assertEquals("value", i.arg);
    }

    public interface Contract {
        void meth(String arg1);
    }

    public interface Contract2 {
        void meth2(String arg);
    }

    private static class Impl implements Contract, Serializable, Contract2 {
        String arg;
        public void meth(String arg1, String arg2) {
            assert false : "should be ignored";
        }
        public void meth(String arg1) {
            this.arg = arg1;
        }
        public void meth2(String arg) {
            this.arg = arg;
        }
        private Object writeReplace() {
            return Channel.current().export(Contract.class, this);
        }
    }

    private static class Task extends CallableBase<Void,Error> {
        private final Contract c;
        Task(Contract c) {
            this.c = c;
        }
        public Void call() throws Error {
            c.meth("value");
            return null;
        }
    }

    private static class Task2 extends CallableBase<Void,Error> {
        private final Contract2 c;
        Task2(Contract2 c) {
            this.c = c;
        }
        public Void call() throws Error {
            c.meth2("value");
            return null;
        }
    }


    public void testAsyncCall() throws Exception {
        final AsyncImpl i = new AsyncImpl();
        AsyncContract c = channel.export(AsyncContract.class, i);

        synchronized (i) {
            channel.call(new AsyncTask(c));
            assertEquals(null, i.arg);  // async call should be blocking

            while (i.arg==null)
                i.wait();
            assertEquals("value", i.arg);  // once we let the call complete, we should see 'value'
        }
    }

    public interface AsyncContract {
        @Asynchronous
        void meth(String arg1);
    }

    private static class AsyncImpl implements AsyncContract, Serializable {
        String arg;
        public void meth(String arg1) {
            synchronized (this) {
                this.arg = arg1;
                notifyAll();
            }
        }
    }

    private static class AsyncTask extends CallableBase<Void,Error> {
        private final AsyncContract c;
        AsyncTask(AsyncContract c) {
            this.c = c;
        }
        public Void call() throws Error {
            c.meth("value");
            return null;
        }
    }
}
