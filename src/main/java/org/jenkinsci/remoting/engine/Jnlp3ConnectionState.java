/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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
package org.jenkinsci.remoting.engine;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Represents the connection state of a {@link JnlpProtocol3Handler} connection.
 *
 * @since 3.0
 */
@Deprecated
public class Jnlp3ConnectionState extends LegacyJnlpConnectionState {

    /**
     * The channel ciphers.
     */
    private ChannelCiphers channelCiphers;
    /**
     * The new cookie.
     */
    private String newCookie;

    /**
     * {@inheritDoc}
     */
    protected Jnlp3ConnectionState(@Nonnull Socket socket,
                                   List<? extends JnlpConnectionStateListener> listeners) throws IOException {
        super(socket, listeners);
    }

    /**
     * Returns the channel ciphers.
     *
     * @return the channel ciphers.
     */
    public ChannelCiphers getChannelCiphers() {
        return channelCiphers;
    }

    /**
     * Returns the new cookie for this connection.
     *
     * @return the new cookie for this connection.
     */
    public String getNewCookie() {
        return newCookie;
    }

    /**
     * Sets the channel ciphers.
     *
     * @param channelCiphers the channel ciphers.
     */
    /*package*/ void setChannelCiphers(ChannelCiphers channelCiphers) {
        this.channelCiphers = channelCiphers;
    }

    /**
     * Sets the new cookie.
     *
     * @param newCookie the new cookie.
     */
    /*package*/ void setNewCookie(String newCookie) {
        this.newCookie = newCookie;
    }
}
