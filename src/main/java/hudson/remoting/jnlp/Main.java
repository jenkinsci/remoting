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
package hudson.remoting.jnlp;

import hudson.remoting.Launcher;
import java.io.IOException;
import org.kohsuke.args4j.CmdLineException;

/**
 * Previous entry point to pseudo-JNLP agent.
 *
 * <p>See also {@code jenkins-agent.jnlp.jelly} in the core.
 *
 * @author Kohsuke Kawaguchi
 * @deprecated use {@link Launcher}
 */
@Deprecated
public class Main extends Launcher {

    private static volatile boolean deprecationWarningLogged;

    public static void main(String... args) throws IOException, InterruptedException {
        logDeprecation();
        Launcher.main(args);
    }

    @Override
    public void run() throws CmdLineException, IOException, InterruptedException {
        logDeprecation();
        super.run();
    }

    private static void logDeprecation() {
        if (deprecationWarningLogged) {
            return;
        }
        System.err.println(
                "WARNING: Using deprecated entrypoint \"java -cp agent.jar hudson.remoting.jnlp.Main\". Use \"java -jar agent.jar\" instead.");
        deprecationWarningLogged = true;
    }
}
