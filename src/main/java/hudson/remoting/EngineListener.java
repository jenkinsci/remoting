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

import javax.swing.SwingUtilities;

/**
 * Receives status notification from {@link Engine}.
 *
 * <p>
 * The callback will be invoked on a non-GUI thread, so if the implementation
 * wants to touch Swing, {@link SwingUtilities#invokeLater(Runnable)} would be needed.
 *
 * <p>
 * To implement this interface outside this module, extend from {@link EngineListenerAdapter}
 * instead to protect against method additions in the future.
 *
 * @author Kohsuke Kawaguchi
 */
public interface EngineListener {
    /**
     * Status message that indicates the progress of the operation.
     */
    void status(String msg);

    /**
     * Status message, with additional stack trace that indicates an error that was recovered.
     */
    void status(String msg, Throwable t);

    /**
     * Fatal error that's non recoverable.
     */
    void error(Throwable t);

    /**
     * Called when a connection is terminated.
     */
    void onDisconnect();

    /**
     * Called when a re-connection is about to be attempted.
     * @since 2.0
     */
    void onReconnect();
}
