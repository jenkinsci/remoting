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

import org.jenkinsci.remoting.RoleChecker;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import java.util.logging.Level;

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
    private static final long  DEFAULT_TIMEOUT      = TimeUnit.MINUTES.toMillis(1);
    private static final long  DEFAULT_INTERVAL     = TimeUnit.MINUTES.toMillis(10);
    private static final int   DEFAULT_MAX_TIMEOUTS = 4;

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

    /**
     * Tolerate max timeouts before assuming ping error.
     */
    private final int maxTimeouts;

    public PingThread(Channel channel, long timeout, long interval, int maxTimeouts) {
        super("Ping thread for channel "+channel);
        this.channel = channel;
        this.timeout = timeout;
        this.interval = interval;
        this.maxTimeouts = maxTimeouts;
        setDaemon(true);
        setUncaughtExceptionHandler((t, e) -> {
            LOGGER.log(Level.SEVERE, "Uncaught exception in PingThread " + t, e);
            onDead(e);
        });
    }

    public PingThread(Channel channel, long timeout, long interval) {
        this(channel, timeout, interval, DEFAULT_MAX_TIMEOUTS);
    }

    public PingThread(Channel channel, long interval) {
        this(channel, DEFAULT_TIMEOUT, interval);
    }

    public PingThread(Channel channel) {
        this(channel, DEFAULT_INTERVAL);
    }

    public void run() {
        try {
            while(true) {
                long nextCheck = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(interval);

                ping();

                // wait until the next check
                long diff;
                while((diff = nextCheck - System.nanoTime()) > 0) {
                    Thread.sleep(TimeUnit.NANOSECONDS.toMillis(diff));
                }
            }
        } catch (ChannelClosedException e) {
            LOGGER.fine(getName()+" is closed. Terminating");
        } catch (IOException e) {
            onDead(e);
        } catch (InterruptedException e) {
            // use interruption as a way to terminate the ping thread.
            LOGGER.fine(getName()+" is interrupted. Terminating");
        }
    }

    private void ping() throws IOException, InterruptedException {
        LOGGER.log(Level.FINE, "pinging {0}", channel.getName());

        final long start = System.currentTimeMillis();

        for (int timeouts = 0; timeouts < maxTimeouts; ++timeouts) {
            Future<?> f = channel.callAsync(new Ping());

            try {
                LOGGER.log(Level.FINE, "waiting {0}s on {1}",
                           new Object[] {TimeUnit.MILLISECONDS.toSeconds(timeout), channel.getName()});
                f.get(timeout, TimeUnit.MILLISECONDS);
                LOGGER.log(Level.FINE, "ping succeeded on {0}", channel.getName());
                return;

            } catch (ExecutionException e) {
                if (e.getCause() instanceof RequestAbortedException)
                    return; // connection has shut down orderly.
                onDead(e);
                return;

            } catch (TimeoutException e) {
                LOGGER.log(Level.WARNING, "ping timeout {0}/{1} on {2}",
                           new Object[] {timeouts, maxTimeouts, channel.getName()});
            }
        }

        onDead(new TimeoutException
               ( String.format("Ping started at %d hasn't completed by %d",
                               start, System.currentTimeMillis()) ));
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
        onDead();   // fall back
    }

    private static final class Ping implements Callable<Void, IOException> {
        private static final long serialVersionUID = 1L;

        public Void call() throws IOException {
            return null;
        }

        @Override
        public void checkRoles(RoleChecker checker) throws SecurityException {
            // this callable is literally no-op, can't get any safer than that
        }
    }

    private static final Logger LOGGER = Logger.getLogger(PingThread.class.getName());
}
