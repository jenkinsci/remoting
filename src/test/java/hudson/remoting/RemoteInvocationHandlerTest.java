package hudson.remoting;

import java.io.Serializable;

public class RemoteInvocationHandlerTest extends RmiTestBase {

    public void testMethodSelection() throws Exception {
        final Impl i = new Impl();
        channel.call(new Task(i));
        assertEquals("value", i.arg);
    }

    public interface Contract {
        void meth(String arg1);
    }

    private static class Impl implements Contract, Serializable {
        String arg;
        public void meth(String arg1, String arg2) {
            assert false : "should be ignored";
        }
        public void meth(String arg1) {
            this.arg = arg1;
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
