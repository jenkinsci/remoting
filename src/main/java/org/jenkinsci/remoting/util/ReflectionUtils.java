/*
 * The MIT License
 *
 * Copyright (c) 2016, Oleg Nenashev, CloudBees, Inc.
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

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckReturnValue;
import javax.annotation.Nonnull;
import java.lang.reflect.AccessibleObject;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides some reflection util methods.
 * This class is not designed for the external use.
 * @author Oleg Nenashev
 */
@Restricted(NoExternalUse.class)
public class ReflectionUtils {

    private ReflectionUtils() {
        // Instantiation is prohibited
    }

    /**
     * Makes a method accessible using reflection.
     * There is no automatic accessibility status recovery.
     * @param object Object to be modified
     * @throws PrivilegedActionException Missing permissions or other modification error.
     *      This exception cannot happen if the object is accessible when the method is invoked.
     */
    public static void makeAccessible(final @Nonnull AccessibleObject object) throws PrivilegedActionException {
        if (object.isAccessible()) {
            // No need to change anything
            return;
        }
        AccessController.doPrivileged(
            new PrivilegedExceptionAction<Void>() {
                @Override
                public Void run() throws Exception {
                    object.setAccessible(true);
                    return null;
                }
            });
    }


    /**
     * Makes a method accessible using reflection.
     * In the case of {@link PrivilegedActionException} logs them without propagating exception.
     * There is no automatic accessibility status recovery.
     * @param object Object to be modified
     * @param logger Logger for errors
     * @param level  Logging level for errors
     * @return {@code true} on success path, {@code false} if the modification error happens
     */
    @CheckReturnValue
    public static boolean makeAccessibleOrLog(final @Nonnull AccessibleObject object,
                                              final @Nonnull Logger logger,
                                              final @Nonnull Level level) {
        try {
            makeAccessible(object);
        } catch (PrivilegedActionException ex) {
            logger.log(level, String.format("Cannot make the field %s accessible", object), ex);
            return false;
        }
        return true;
    }

}
