/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc.
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

import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Kohsuke Kawaguchi
 */
public class NonSerializableExceptionTest {
    /**
     * Makes sure non-serializable exceptions are gracefully handled.
     *
     * HUDSON-1041.
     */
    @ParameterizedTest
    @MethodSource(ChannelRunners.PROVIDER_METHOD)
    public void test1(ChannelRunner channelRunner) throws Throwable {
        channelRunner.withChannel(channel -> {
            final ProxyException p = assertThrows(ProxyException.class, () -> channel.call(new Failure()));
            // verify that we got the right kind of exception
            assertTrue(p.getMessage().contains("NoneSerializableException"));
            assertTrue(p.getMessage().contains("message1"));
            ProxyException nested = p.getCause();
            assertTrue(nested.getMessage().contains("SocketException"));
            assertTrue(nested.getMessage().contains("message2"));
            assertNull(nested.getCause());
        });
    }

    private static final class NoneSerializableException extends Exception {
        @SuppressWarnings("unused")
        private final Object o = new Object(); // this is not serializable

        private NoneSerializableException(String msg, Throwable cause) {
            super(msg, cause);
        }

        private static final long serialVersionUID = 1L;
    }

    private static final class Failure extends CallableBase<Object, Throwable> {
        @Override
        public Object call() throws Throwable {
            throw new NoneSerializableException("message1", new SocketException("message2"));
        }

        private static final long serialVersionUID = 1L;
    }
}
