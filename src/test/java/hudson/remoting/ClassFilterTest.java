package hudson.remoting;

import hudson.remoting.Channel.Mode;
import hudson.remoting.CommandTransport.CommandReceiver;
import org.jenkinsci.remoting.nio.NioChannelBuilder;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;

import static org.junit.Assert.*;

/**
 * Tests the effect of {@link ClassFilter}.
 *
 * <p>
 * This test code targets each of the known layers where object serialization is used.
 * Specifically, those are {@link ObjectInputStream} (and subtypes) created in:
 *
 * <ul>
 * <li>{@link Capability#read(InputStream)}
 * <li>{@link UserRequest#deserialize(Channel, byte[], ClassLoader)},
 * <li>{@link ChannelBuilder#makeTransport(InputStream, OutputStream, Mode, Capability)}
 * <li>{@link AbstractByteArrayCommandTransport#setup(Channel, CommandReceiver)}
 * <li>{@link AbstractSynchronousByteArrayCommandTransport#read()}
 * </ul>
 *
 * @author Kohsuke Kawaguchi
 */
public class ClassFilterTest implements Serializable {

    /**
     * North can defend itself from south but not the other way around.
     */
    private transient DualSideChannelRunner runner;

    private transient Channel north, south;

    private static class TestFilter extends ClassFilter {
        @Override
        protected boolean isBlacklisted(String name) {
            return name.contains("Security218");
        }
    }

    /**
     * Set up a channel pair where north side is well protected from south side but not the other way around.
     */
    private void setUp() throws Exception {
        setUp(new InProcessRunner() {
            @Override
            protected ChannelBuilder configureNorth() {
                return super.configureNorth()
                        .withClassFilter(new TestFilter());
            }
        });
    }

    /**
     * Set up a channel pair with no capacity. In the context of this test,
     * the lack of chunked encoding triggers a different transport implementation, and the lack of
     * multi-classloader support triggers {@link UserRequest} to select a different deserialization mechanism.
     */
    private void setUpWithNoCapacity() throws Exception {
        setUp(new InProcessRunner() {
            @Override
            protected ChannelBuilder configureNorth() {
                return super.configureNorth()
                        .withCapability(Capability.NONE)
                        .withClassFilter(new TestFilter());
            }

            @Override
            protected ChannelBuilder configureSouth() {
                return super.configureSouth().withCapability(Capability.NONE);
            }
        });
    }

    private void setUp(DualSideChannelRunner runner) throws Exception {
        this.runner = runner;
        north = runner.start();
        south = runner.getOtherSide();
        clearRecord();
    }

    @After
    public void tearDown() throws Exception {
        if (runner!=null)
            runner.stop(north);
    }

    /**
     * Makes sure {@link Capability#read(InputStream)} rejects unexpected payload.
     */
    @Test
    public void capabilityRead() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(Mode.TEXT.wrap(baos));
        oos.writeObject(new Security218("rifle"));
        oos.close();

        try {
            Capability.read(new ByteArrayInputStream(baos.toByteArray()));
        } catch (SecurityException e) {
            assertEquals("Rejected: "+Security218.class.getName(), e.getMessage());
        }
    }

    /**
     * This test case targets object stream created in
     * {@link UserRequest#deserialize(Channel, byte[], ClassLoader)} with multiclassloader support.
     */
    @Test
    public void userRequest() throws Exception {
        setUp();
        userRequestTestSequence();
    }

    /**
     * Variant of {@link #userRequest()} test that targets
     * {@link UserRequest#deserialize(Channel, byte[], ClassLoader)} *without* multiclassloader support.
     */
    @Test
    public void userRequest_singleClassLoader() throws Exception {
        setUpWithNoCapacity();
        userRequestTestSequence();
    }

    private void userRequestTestSequence() throws Exception {
        // control case to prove that an attack will succeed to without filter.
        fire("caesar", north);
        assertTrue(getAttack().contains("caesar>south"));

        clearRecord();

        // the test case that should be rejected by a filter.
        try {
            fire("napoleon", south);
            fail("Expected call to fail");
        } catch (IOException e) {
            String msg = toString(e);
            assertTrue(msg, msg.contains("Rejected: " + Security218.class.getName()));
            assertEquals("", getAttack());
        }
    }

    /**
     * Sends an attack payload over {@link Channel#call(Callable)}
     */
    private void fire(String name, Channel from) throws Exception {
        final Security218 a = new Security218(name);
        from.call(new CallableBase<Void, IOException>() {
            @Override
            public Void call() throws IOException {
                a.toString();   // this will ensure 'a' gets sent over
                return null;
            }
        });
    }

    /**
     * This test case targets command stream created in
     * {@link AbstractSynchronousByteArrayCommandTransport#read()}, which is used
     * by {@link ChunkedCommandTransport}.
     */
    @Test
    public void transport_chunking() throws Exception {
        setUp();
        commandStreamTestSequence();
    }

    /**
     * This test case targets command stream created in
     * {@link ChannelBuilder#makeTransport(InputStream, OutputStream, Mode, Capability)}
     * by not having the chunking capability.
     */
    @Test
    public void transport_non_chunking() throws Exception {
        setUpWithNoCapacity();
        commandStreamTestSequence();
    }

    /**
     * This test case targets command stream created in
     * {@link AbstractByteArrayCommandTransport#setup(Channel, CommandReceiver)}
     */
    @Test
    public void transport_nio() throws Exception {
        setUp(new NioSocketRunner() {
            @Override
            protected NioChannelBuilder configureNorth() {
                return super.configureNorth()
                        .withClassFilter(new TestFilter());
            }
        });
        commandStreamTestSequence();
    }

    private void commandStreamTestSequence() throws Exception {
        // control case to prove that an attack will succeed to without filter.
        north.send(new Security218("eisenhower"));
        north.syncIO(); // any synchronous RPC call would do
        assertTrue(getAttack().contains("eisenhower>south"));

        clearRecord();

        // the test case that should be rejected by a filter
        try {
            south.send(new Security218("hitler"));
            north.syncIO(); // transport_chunking hangs if this is 'south.syncIO', because somehow south
                            // doesn't notice that the north has aborted and the connection is lost.
                            // this is indicative of a larger problem, but one that's not related to
                            // SECURITY-218 at hand, so I'm going to leave this with 'north.syncIO'
                            // it still achieves the effect of blocking until the command is processed by north,
                            // because the response from south back to north would have to come after Security218
                            // command.

            // fail("the receiving end will abort after receiving Security218, so syncIO should fail");
            // ... except for NIO, which just discards that command and keeps on
//        } catch (RequestAbortedException e) {
//            // other transport kills the connection
//            String msg = toString(e);
//            assertTrue(msg, msg.contains("Rejected: " + Security218.class.getName()));
        } catch (Exception e) {
            e.printStackTrace();
        }

        // either way, the attack payload should have been discarded before it gets deserialized
        assertEquals("", getAttack());
    }

    private String toString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    /**
     * An attack payload that leaves a trace on the receiver side if it gets read from the stream.
     * Extends from {@link Command} to be able to test command stream.
     */
    static class Security218 extends Command implements Serializable {
        private final String attack;

        public Security218(String attack) {
            this.attack = attack;
        }

        private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            ois.defaultReadObject();
            System.setProperty("attack", attack + ">" + Channel.current().getName());
        }

        @Override
        protected void execute(Channel channel) {
            // nothing to do here
        }
    }

    private String getAttack() {
        return System.getProperty("attack");
    }

    private void clearRecord() {
        System.setProperty("attack", "");
    }
}