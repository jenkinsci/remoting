package hudson.remoting;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jenkinsci.remoting.util.AnonymousClassWarnings;

/**
 * Restricts what classes can be received through remoting.
 * The same filter is also applied by Jenkins core for XStream serialization.
 * @author Kohsuke Kawaguchi
 * @since 2.53
 */
public abstract class ClassFilter {
    /**
     * Property to set to <b>override</b> the blacklist used by {@link #STANDARD} with a different set.
     * The location should point to a a file containing regular expressions (one per line) of classes to blacklist.
     * If this property is set but the file can not be read the default blacklist will be used.
     * @since 2.53.2
     * @deprecated use {@link #setDefault} as needed
     */
    @Deprecated
    public static final String FILE_OVERRIDE_LOCATION_PROPERTY =
            "hudson.remoting.ClassFilter.DEFAULTS_OVERRIDE_LOCATION";

    private static final Logger LOGGER = Logger.getLogger(ClassFilter.class.getName());

    /**
     * Whether a given class should be blocked, before even attempting to load that class.
     * @param name {@link Class#getName}
     * @return false by default; override to return true to blacklist this class
     */
    public boolean isBlacklisted(@NonNull String name) {
        return false;
    }

    /**
     * Whether a given class should be blocked, after having loaded that class.
     * @param c a loaded class
     * @return false by default; override to return true to blacklist this class
     */
    public boolean isBlacklisted(@NonNull Class<?> c) {
        return false;
    }

    /**
     * API version of {@link #isBlacklisted(String)} SPI.
     * @return the same {@code name}
     * @throws SecurityException if it is blacklisted
     */
    public final String check(String name) {
        if (isBlacklisted(name)) {
            throw new SecurityException("Rejected: " + name + "; see https://jenkins.io/redirect/class-filter/");
        }
        return name;
    }

    /**
     * API version of {@link #isBlacklisted(Class)} SPI.
     * @return the same {@code c}
     * @throws SecurityException if it is blacklisted
     */
    public final Class<?> check(Class<?> c) {
        if (isBlacklisted(c)) {
            throw new SecurityException("Rejected: " + c.getName() + "; see https://jenkins.io/redirect/class-filter/");
        }
        return c;
    }

    private static final String[] DEFAULT_PATTERNS = {
        "^bsh[.].*",
        "^com[.]google[.]inject[.].*",
        "^com[.]mchange[.]v2[.]c3p0[.].*",
        "^com[.]sun[.]jndi[.].*",
        "^com[.]sun[.]corba[.].*",
        "^com[.]sun[.]javafx[.].*",
        "^com[.]sun[.]org[.]apache[.]regex[.]internal[.].*",
        "^java[.]awt[.].*",
        "^java[.]lang[.]reflect[.]Method$",
        "^java[.]rmi[.].*",
        "^javax[.]management[.].*",
        "^javax[.]naming[.].*",
        "^javax[.]script[.].*",
        "^javax[.]swing[.].*",
        "^net[.]sf[.]json[.].*",
        "^org[.]apache[.]commons[.]beanutils[.].*",
        "^org[.]apache[.]commons[.]collections[.]functors[.].*",
        "^org[.]apache[.]myfaces[.].*",
        "^org[.]apache[.]wicket[.].*",
        ".*org[.]apache[.]xalan.*",
        "^org[.]codehaus[.]groovy[.]runtime[.].*",
        "^org[.]hibernate[.].*",
        "^org[.]python[.].*",
        "^org[.]springframework[.](?!(\\p{Alnum}+[.])*\\p{Alnum}*Exception$).*",
        "^sun[.]rmi[.].*",
        "^javax[.]imageio[.].*",
        "^java[.]util[.]ServiceLoader$",
        "^java[.]net[.]URLClassLoader$",
        "^java[.]security[.]SignedObject$"
    };

    /**
     * The currently used default.
     * Defaults to {@link #STANDARD}.
     */
    public static final ClassFilter DEFAULT = new ClassFilter() {
        @Override
        public boolean isBlacklisted(@NonNull Class<?> c) {
            return CURRENT_DEFAULT.isBlacklisted(c);
        }

        @Override
        public boolean isBlacklisted(@NonNull String name) {
            return CURRENT_DEFAULT.isBlacklisted(name);
        }
    };

    private static @NonNull ClassFilter CURRENT_DEFAULT;

    /**
     * Changes the effective value of {@link #DEFAULT}.
     * @param filter a new default to set; may or may not delegate to {@link #STANDARD}
     * @since 3.16
     */
    public static void setDefault(@NonNull ClassFilter filter) {
        CURRENT_DEFAULT = filter;
    }

    /**
     * A set of sensible default filtering rules to apply, based on a configurable blacklist.
     */
    public static final ClassFilter STANDARD;

    static {
        try {
            STANDARD = createDefaultInstance();
        } catch (ClassFilterException ex) {
            LOGGER.log(Level.SEVERE, "Default class filter cannot be initialized. Remoting will not start", ex);
            throw new ExceptionInInitializerError(ex);
        }
        CURRENT_DEFAULT = STANDARD;
    }

    /**
     * Adds an additional exclusion to {@link #STANDARD}.
     *
     * Does nothing if the default list has already been customized via {@link #FILE_OVERRIDE_LOCATION_PROPERTY}.
     * This API is not supposed to be used anywhere outside Jenkins core, calls for other sources may be rejected later.
     * @param filter a regular expression for {@link Class#getName} which, if matched according to {@link Matcher#matches}, will blacklist the class
     * @throws ClassFilterException Filter pattern cannot be applied.
     *                              It means either unexpected processing error or rejection by the internal logic.
     * @since 3.11
     * @deprecated use {@link #setDefault} as needed
     */
    @Deprecated
    public static void appendDefaultFilter(Pattern filter) throws ClassFilterException {
        if (System.getProperty(FILE_OVERRIDE_LOCATION_PROPERTY) == null) {
            ((RegExpClassFilter) STANDARD).add(filter.toString());
        }
    }

    /**
     * No filtering whatsoever.
     */
    public static final ClassFilter NONE = new ClassFilter() {};

    /**
     * The default filtering rules to apply, unless the context guarantees the trust between two channels. The defaults
     * values provide for user specified overrides - see {@link #FILE_OVERRIDE_LOCATION_PROPERTY}.
     */
    /*package*/ static ClassFilter createDefaultInstance() throws ClassFilterException {
        try {
            List<String> patternOverride = loadPatternOverride();
            if (patternOverride != null) {
                LOGGER.log(Level.FINE, "Using user specified overrides for class blacklisting");
                return new RegExpClassFilter(patternOverride.toArray(new String[0]));
            } else {
                LOGGER.log(Level.FINE, "Using default in built class blacklisting");
                return new RegExpClassFilter(DEFAULT_PATTERNS);
            }
        } catch (Error e) {
            // when being used by something like XStream the actual cause gets swallowed
            LOGGER.log(Level.SEVERE, "Failed to initialize the default class filter", e);
            throw e;
        }
    }

    @CheckForNull
    private static List<String> loadPatternOverride() {
        String prop = System.getProperty(FILE_OVERRIDE_LOCATION_PROPERTY);
        if (prop == null) {
            return null;
        }

        LOGGER.log(Level.FINE, "Attempting to load user provided overrides for ClassFiltering from ''{0}''.", prop);
        File f = new File(prop);
        if (!f.exists() || !f.canRead()) {
            throw new Error("Could not load user provided overrides for ClassFiltering from as " + prop
                    + " does not exist or is not readable.");
        }

        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(prop), Charset.defaultCharset()));
            ArrayList<String> patterns = new ArrayList<>();
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                try {
                    Pattern.compile(line);
                    patterns.add(line);
                } catch (PatternSyntaxException pex) {
                    throw new Error(
                            "Error compiling blacklist expressions - '" + line + "' is not a valid regular expression.",
                            pex);
                }
            }
            return patterns;
        } catch (IOException ex) {
            throw new Error(
                    "Could not load user provided overrides for ClassFiltering from as " + prop
                            + " does not exist or is not readable.",
                    ex);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException ioEx) {
                    LOGGER.log(Level.WARNING, "Failed to cleanly close input stream", ioEx);
                }
            }
        }
    }

    /**
     * A class that uses a given set of regular expression patterns to determine if the class is blacklisted.
     */
    @SuppressFBWarnings(
            value = "REDOS",
            justification =
                    "In an odd usage, this pattern is used to determine if another pattern matches it and not to match a string to it. REDOS doesn't apply.")
    private static final class RegExpClassFilter extends ClassFilter {

        /**
         * Any regex that is {@code ^some[.]package[.]name[.].*} or {@code ^some\.package\.name\.*} is really just a
         * {@link String#startsWith(String)} test and we can reduce CPU usage by performing that test explicitly as
         * well as reduce GC pressure.
         */
        private static final Pattern OPTIMIZE1 =
                Pattern.compile("^\\^(([\\p{L}_$][\\p{L}\\p{N}_$]*(\\.|\\[\\.\\])?)+)\\.\\*$");

        /**
         * Any regex that is {@code ^\Qsome.package.name\E.*} is really just a {@link String#startsWith(String)}
         * test and we can reduce CPU usage by performing that test explicitly as well as reduce GC pressure.
         */
        private static final Pattern OPTIMIZE2 = Pattern.compile("^\\^\\Q[^\\\\]+\\\\E\\.\\*$");

        private final List<Object> blacklistPatterns;

        RegExpClassFilter(String[] patterns) throws ClassFilterException {
            blacklistPatterns = new ArrayList<>(patterns.length);
            for (String pattern : patterns) {
                add(pattern);
            }
        }

        void add(String pattern) throws ClassFilterException {

            if (OPTIMIZE1.matcher(pattern).matches()) {
                // this is a simple startsWith test, no need to slow things down with a regex
                blacklistPatterns.add(pattern.substring(1, pattern.length() - 2).replace("[.]", "."));
            } else if (OPTIMIZE2.matcher(pattern).matches()) {
                // this is a simple startsWith test, no need to slow things down with a regex
                blacklistPatterns.add(pattern.substring(3, pattern.length() - 4));
            } else {
                final Pattern regex;
                try {
                    regex = Pattern.compile(pattern);
                } catch (PatternSyntaxException ex) {
                    throw new ClassFilterException("Cannot add RegExp class filter", ex);
                }
                blacklistPatterns.add(regex);
            }
        }

        @Override
        public boolean isBlacklisted(@NonNull String name) {
            for (Object p : blacklistPatterns) {
                if (p instanceof Pattern && ((Pattern) p).matcher(name).matches()) {
                    return true;
                } else if (p instanceof String && name.startsWith((String) p)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isBlacklisted(@NonNull Class<?> c) {
            AnonymousClassWarnings.check(c);
            return false;
        }

        /**
         * Report the patterns that it's using to help users verify the use of custom filtering rule
         * and inspect its content at runtime if necessary.
         */
        @Override
        public String toString() {
            return blacklistPatterns.toString();
        }
    }

    /**
     * Class for propagating exceptions in {@link ClassFilter}.
     * @since 3.11
     * @deprecated Only used by deprecated {@link #appendDefaultFilter}.
     */
    @Deprecated
    public static class ClassFilterException extends Exception {

        @CheckForNull
        final String pattern;

        public ClassFilterException(String message, PatternSyntaxException ex) {
            this(message, ex, ex.getPattern());
        }

        public ClassFilterException(String message, @CheckForNull String pattern) {
            this(message, new IllegalStateException(message), pattern);
        }

        public ClassFilterException(String message, Throwable cause, @CheckForNull String pattern) {
            super(message, cause);
            this.pattern = pattern;
        }

        @CheckForNull
        public String getPattern() {
            return pattern;
        }

        @Override
        public String getMessage() {
            return super.getMessage() + ". Pattern: " + pattern;
        }
    }
}
