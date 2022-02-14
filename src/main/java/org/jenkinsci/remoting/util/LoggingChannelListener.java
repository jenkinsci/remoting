/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
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

package org.jenkinsci.remoting.util;

import hudson.remoting.Channel;
import hudson.remoting.Command;
import hudson.remoting.Request;
import hudson.remoting.Response;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Channel listener which merely formats events to a logger.
 * @since 3.17
 */
public class LoggingChannelListener extends Channel.Listener {

    private final Logger logger;
    private final Level level;

    public LoggingChannelListener(Logger logger, Level level) {
        this.logger = logger;
        this.level = level;
    }

    @Override
    public void onClosed(Channel channel, IOException cause) {
        if (logger.isLoggable(level)) {
            logger.log(level, null, cause);
        }
    }

    @Override
    public void onRead(Channel channel, Command cmd, long blockSize) {
        if (logger.isLoggable(level)) {
            logger.log(level, channel.getName() + " read " + blockSize + ": " + cmd);
        }
    }

    @Override
    public void onWrite(Channel channel, Command cmd, long blockSize) {
        if (logger.isLoggable(level)) {
            logger.log(level, channel.getName() + " wrote " + blockSize + ": " + cmd);
        }
    }

    @Override
    public void onResponse(Channel channel, Request<?, ?> req, Response<?, ?> rsp, long totalTime) {
        if (logger.isLoggable(level)) {
            logger.log(level, channel.getName() + " received response in " + totalTime / 1_000_000 + "ms: " + req);
        }
    }

    @Override
    public void onJar(Channel channel, File jar) {
        if (logger.isLoggable(level)) {
            logger.log(level, channel.getName() + " sending " + jar + " of length " + jar.length());
        }
    }
}
