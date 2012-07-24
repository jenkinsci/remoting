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

    private static class Task implements Callable<Void,Error> {
        private final Contract c;
        Task(Contract c) {
            this.c = c;
        }
        public Void call() throws Error {
            c.meth("value");
            return null;
        }
    }

}
