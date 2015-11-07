package hudson.remoting;

/**
 * Filter classes that can be seen.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.53
 */
class FilteringClassLoader extends ClassLoader {
    private final ClassLoader actual;

    public FilteringClassLoader(ClassLoader actual) {
        // intentionally not passing 'actual' as the parent classloader to the super type
        // to prevent accidental bypassing of a filter.
        this.actual = actual;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        if (isBlacklisted(name))    throw new ClassNotFoundException(name);
        Class<?> c = actual.loadClass(name);
        if (isBlacklisted(c))       throw new ClassNotFoundException(name);
        return c;
    }

    protected boolean isBlacklisted(String name) {
        return false;
    }

    protected boolean isBlacklisted(Class c) {
        return false;
    }
}
