/*
 * The MIT License
 * 
 * Copyright (c) 2004-2015, Sun Microsystems, Inc., Kohsuke Kawaguchi, CloudBees, Inc.
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

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Engine utility methods.
 * 
 * Internal class. DO NOT USE FROM OUTSIDE.
 *
 * @author Akshay Dayal
 */
public class EngineUtil {

    /**
     * Read until '\n' and returns it as a string.
     *
     * @param inputStream The input stream to read from.
     * @return The line read.
     * @throws IOException
     */
    public static String readLine(InputStream inputStream) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        while (true) {
            int ch = inputStream.read();
            if (ch<0 || ch=='\n') {
                // Trim off possible '\r'
                return byteArrayOutputStream.toString("UTF-8").trim();
            }
            byteArrayOutputStream.write(ch);
        }
    }

    /**
     * Read a certain amount of characters from the stream.
     *
     * @param inputStream The input stream to read from.
     * @param len The amount of characters to read.
     * @return The characters read.
     * @throws IOException
     */
    public static String readChars(InputStream inputStream, int len) throws IOException {
        byte[] buf = new byte[len];
        for (int i = 0; i < len; i++) {
            buf[i] = (byte)inputStream.read();
        }

        return new String(buf,"UTF-8");
    }

    /**
     * Read headers from a response.
     *
     * @param inputStream The input stream to read from.
     * @return The set of headers stored in a {@link Properties}.
     * @throws IOException
     */
    protected static Properties readResponseHeaders(InputStream inputStream) throws IOException {
        Properties response = new Properties();
        while (true) {
            String line = EngineUtil.readLine(inputStream);
            if (line.length()==0) {
                return response;
            }
            int idx = line.indexOf(':');
            response.put(line.substring(0,idx).trim(), line.substring(idx+1).trim());
        }
    }

    /**
      * Closes the item and logs error to the log in the case of error.
      * Logging will be performed on the {@code WARNING} level.
      * @param toClose Item to close. Nothing will happen if it is {@code null}
      * @param logger Logger, which receives the error
      * @param closeableName Name of the closeable item
      * @param closeableOwner String representation of the closeable holder
      */
    static void closeAndLogFailures(@CheckForNull Closeable toClose, @Nonnull Logger logger,
                                    @Nonnull String closeableName, @Nonnull String closeableOwner) {
        if (toClose == null) {
           return;
        }
        try {
            toClose.close();
        } catch(IOException ex) {
            logger.log(Level.WARNING, String.format("Failed to close %s of %s", closeableName, closeableOwner), ex);
        }
    }
}
