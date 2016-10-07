package org.jenkinsci.remoting.nio;

import org.jenkinsci.remoting.util.ReflectionUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.security.PrivilegedActionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Extracts out {@link SelectableChannel} from {@link InputStream} or {@link OutputStream}.
 *
 * This hides the hack that works around the fact that {@link FileChannel} is not a {@link SelectableChannel}.
 *
 * <p>
 * JDK didn't make {@link FileChannel} selectable because it's not selectable on Windows.
 * But on POSIX, select API does support arbitrary file descriptors, including pipes and regular files.
 *
 * <p>
 * Methods on this class takes various JDK objects that own {@link FileDescriptor} and
 * creates a {@link SocketChannel} object that points to the same file descriptor, as a hack.
 *
 * Note that since it is not a real socket, various socket specific operations on {@link SocketChannel}
 * will fail, most notably {@link Object#toString()}
 *
 * @author Kohsuke Kawaguchi
 * @since 2.38
 */
public class SelectableFileChannelFactory {
    protected FileInputStream unwrap(InputStream i) {
        if (i instanceof BufferedInputStream) {
            try {
                Field $in = FilterInputStream.class.getDeclaredField("in");
                ReflectionUtils.makeAccessible($in);
                return unwrap((InputStream)$in.get(i));
            } catch (NoSuchFieldException e) {
                warn(e);
                return null;
            } catch (IllegalAccessException | PrivilegedActionException e) {
                warn(e);
                return null;
            }
        }
        if (i instanceof FileInputStream) {
            return (FileInputStream) i;
        }
        return null;    // unknown type
    }

    protected FileOutputStream unwrap(OutputStream i) {
        if (i instanceof BufferedOutputStream) {
            try {
                Field $in = FilterOutputStream.class.getDeclaredField("out");
                ReflectionUtils.makeAccessible($in);
                return unwrap((OutputStream)$in.get(i));
            } catch (NoSuchFieldException e) {
                warn(e);
                return null;
            } catch (IllegalAccessException | PrivilegedActionException e) {
                warn(e);
                return null;
            }
        }
        if (i instanceof FileOutputStream) {
            return (FileOutputStream) i;
        }
        return null;    // unknown type
    }

    public SocketChannel create(InputStream in) throws IOException {
        return create(unwrap(in));
    }

    public SocketChannel create(OutputStream out) throws IOException {
        return create(unwrap(out));
    }

    public SocketChannel create(FileInputStream in) throws IOException {
        if (in==null)   return null;
        return create(in.getFD());
    }

    public SocketChannel create(FileOutputStream out) throws IOException {
        if (out==null)   return null;
        return create(out.getFD());
    }

    public SocketChannel create(FileDescriptor fd) {
        if (File.pathSeparatorChar==';')
            return null; // not selectable on Windows

        try {
            Constructor<?> $c = Class.forName("sun.nio.ch.SocketChannelImpl").getDeclaredConstructor(
                SelectorProvider.class,
                FileDescriptor.class,
                InetSocketAddress.class
            );
            ReflectionUtils.makeAccessible($c);

            // increment the FileDescriptor use count since we are giving it to SocketChannel
            Method $m = fd.getClass().getDeclaredMethod("incrementAndGetUseCount");
            ReflectionUtils.makeAccessible($m);
            $m.invoke(fd);

            return (SocketChannel)$c.newInstance(SelectorProvider.provider(), fd, null);
        } catch (NoSuchMethodException e) {
            warn(e);
            return null;
        } catch (SecurityException e) {
            warn(e);
            return null;
        } catch (ClassNotFoundException e) {
            warn(e);
            return null;
        } catch (IllegalAccessException | PrivilegedActionException e) {
            warn(e);
            return null;
        } catch (InvocationTargetException e) {
            warn(e);
            return null;
        } catch (InstantiationException e) {
            warn(e);
            return null;
        }
    }

    private void warn(Exception e) {
        if (!warned) {
            warned = true;
            LOGGER.log(Level.WARNING, "Failed to wrap aFileDescriptor into SocketChannel",e);
        }
    }

    private static boolean warned = false;
    private static final Logger LOGGER = Logger.getLogger(SelectableFileChannelFactory.class.getName());
}
