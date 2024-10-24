/*
 * The MIT License
 *
 * Copyright (c) 2004-2010, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
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

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodically perform a ping.
 *
 * <p>
 * Useful when a connection needs to be kept alive by sending data,
 * or when the disconnection is not properly detected.
 *
 * <p>
 * {@link #onDead()} method needs to be overridden to define
 * what to do when a connection appears to be dead.
 *
 * @author Kohsuke Kawaguchi
 * @since 1.170
 */
public abstract class PingThread extends Thread {
    private final Channel channel;

    /**
     * Time out in milliseconds.
     * If the response doesn't come back by then, the channel is considered dead.
     */
    private final long timeout;

    /**
     * Performs a check every this milliseconds.
     */
    private final long interval;

    public PingThread(Channel channel, long timeout, long interval) {
        super("Ping thread for channel " + channel);
        this.channel = channel;
        this.timeout = timeout;
        this.interval = interval;
        setDaemon(true);
        setUncaughtExceptionHandler((t, e) -> {
            LOGGER.log(Level.SEVERE, "Uncaught exception in PingThread " + t, e);
            onDead(e);
        });
    }

    public PingThread(Channel channel, long interval) {
        this(channel, TimeUnit.MINUTES.toMillis(4), interval);
    }

    public PingThread(Channel channel) {
        this(channel, TimeUnit.MINUTES.toMillis(10));
    }

    @Override
    public void run() {
        try {
            while (true) {
                long nextCheck = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(interval);

                ping();

                // wait until the next check
                long diff;
                while ((diff = nextCheck - System.nanoTime()) > 0) {
                    TimeUnit.NANOSECONDS.sleep(diff);
                }
            }
        } catch (ChannelClosedException e) {
            LOGGER.fine(getName() + " is closed. Terminating");
        } catch (IOException e) {
            onDead(e);
        } catch (InterruptedException e) {
            // use interruption as a way to terminate the ping thread.
            LOGGER.fine(getName() + " is interrupted. Terminating");
        }
    }

    private void ping() throws IOException, InterruptedException {
        LOGGER.log(Level.FINE, "pinging {0}", channel.getName());
        Future<?> f = channel.callAsync(new Ping());
        long start = System.currentTimeMillis();

        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
        long remaining = end - System.nanoTime();

        do {
            LOGGER.log(Level.FINE, "waiting {0}s on {1}", new Object[] {
                TimeUnit.NANOSECONDS.toSeconds(remaining), channel.getName()
            });
            try {
                f.get(Math.max(1, remaining), TimeUnit.NANOSECONDS);
                LOGGER.log(Level.FINE, "ping succeeded on {0}", channel.getName());
                return;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RequestAbortedException) {
                    return; // connection has shut down orderly.
                }
                onDead(e);
                return;
            } catch (TimeoutException e) {
                // get method waits "at most the amount specified in the timeout",
                // so let's make sure that it really waited enough
            }
            remaining = end - System.nanoTime();
        } while (remaining > 0);

        onDead(new TimeoutException(
                "Ping started at " + start + " hasn't completed by " + System.currentTimeMillis())); // .initCause(e)
    }

    /**
     * Called when ping failed.
     *
     * @deprecated as of 2.9
     *      Override {@link #onDead(Throwable)} to receive the cause, but also override this method
     *      and provide a fallback behaviour to be backward compatible with earlier version of remoting library.
     */
    @Deprecated
    protected abstract void onDead();

    /**
     * Called when ping failed.
     *
     * @since 2.9
     */
    protected void onDead(Throwable diagnosis) {
        onDead(); // fall back
    }

    private static final class Ping implements InternalCallable<Void, IOException> {
        private static final long serialVersionUID = 1L;

        @Override
        public Void call() throws IOException {
            return null;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(PingThread.class.getName());
}
