/*
 * The MIT License
 *
 * Copyright (c) 2026, Simon Holesch
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import hudson.remoting.SocketChannelStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the agent connection hanging forever when the controller side goes silent after the TCP
 * handshake, e.g. the process is killed without sending FIN/RST, or a firewall/NAT silently drops the session.
 */
class JnlpAgentEndpointSocketTimeoutTest {

    private static final int READ_TIMEOUT_MS = 1000;

    @Test
    void endpointReadTimeoutActive() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket ignored = server.accept()) {
                    // Accept the connection but never write or close it: simulates a half-dead peer.
                    Thread.sleep(READ_TIMEOUT_MS * 10L);
                } catch (Exception ignored) {
                    // test is wrapping up
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            JnlpAgentEndpoint endpoint = new JnlpAgentEndpoint("localhost", port, null, null, null, null);
            Socket socket = endpoint.open(READ_TIMEOUT_MS);
            try {
                InputStream in = SocketChannelStream.in(socket);

                assertTimeoutPreemptively(
                        Duration.ofMillis(READ_TIMEOUT_MS * 5L),
                        () -> assertThrows(
                                SocketTimeoutException.class,
                                in::read,
                                "read() on a socket from a half-dead peer should time out"));
            } finally {
                socket.close();
            }
        }
    }
}
