package hudson.remoting;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.annotation.CheckForNull;

/**
 * Restricts what classes can be received through remoting.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.53
 */
public abstract class ClassFilter {
    /**
     * Property to set to <b>override</b> the blacklist used by {{@link #DEFAULT} with a different set.
     * The location should point to a a file containing regular expressions (one per line) of classes to blacklist.
     * If this property is set but the file can not be read the default blacklist will be used.
     * @since 2.53.2
     */
    public static final String FILE_OVERRIDE_LOCATION_PROPERTY = "hudson.remoting.ClassFilter.DEFAULTS_OVERRIDE_LOCATION";

    private static final Logger LOGGER = Logger.getLogger(ClassFilter.class.getName());

    protected boolean isBlacklisted(String name) {
        return false;
    }

    protected boolean isBlacklisted(Class c) {
        return false;
    }

	public final String check(String name) {
		if (isBlacklisted(name))
			throw new SecurityException("Rejected: " +name);
		return name;
	}

	public final Class check(Class c) {
		if (isBlacklisted(c))
			throw new SecurityException("Rejected: " +c.getName());
		return c;
	}

    private static final String[] DEFAULT_PATTERNS = {
        "^com[.]google[.]inject[.].*",
        "^com[.]sun[.]jndi[.]rmi[.].*",
        "^java[.]rmi[.].*",
        "^org[.]apache[.]commons[.]beanutils[.].*",
        "^org[.]apache[.]commons[.]collections[.]functors[.].*",
        ".*org[.]apache[.]xalan.*",
        "^org[.]codehaus[.]groovy[.]runtime[.].*",
        "^org[.]hibernate[.].*",
        "^org[.]springframework[.](?!(\\p{Alnum}+[.])*\\p{Alnum}*Exception$).*",
        "^sun[.]rmi[.].*",
    };

    /**
     * A set of sensible default filtering rules to apply,
     * unless the context guarantees the trust between two channels.
     */
    public static final ClassFilter DEFAULT = createDefaultInstance();

    /**
     * No filtering whatsoever.
     */
    public static final ClassFilter NONE = new ClassFilter() {
    };

    /**
     * The default filtering rules to apply, unless the context guarantees the trust between two channels. The defaults
     * values provide for user specified overrides - see {@link #FILE_OVERRIDE_LOCATION_PROPERTY}.
     */
    /*package*/ static ClassFilter createDefaultInstance() {
        try {
            List<String> patternOverride = loadPatternOverride();
            if (patternOverride != null) {
                LOGGER.log(Level.FINE, "Using user specified overrides for class blacklisting");
                return new RegExpClassFilter(patternOverride.toArray(new String[patternOverride.size()]));
            } else {
                LOGGER.log(Level.FINE, "Using default in built class blacklisting");
                return new RegExpClassFilter(DEFAULT_PATTERNS);
            }
        }
        catch (Error e) {
            // when being used by something like XStream the actual cause gets swallowed
            LOGGER.log(Level.SEVERE, "Failed to initialize the default class filter", e);
            throw e;
        }
    }

    @CheckForNull
    private static List<String> loadPatternOverride() {
        String prop = System.getProperty(FILE_OVERRIDE_LOCATION_PROPERTY);
        if (prop==null) {
            return null;
        }

        LOGGER.log(Level.FINE, "Attempting to load user provided overrides for ClassFiltering from ''{0}''.", prop);
        File f = new File(prop);
        if (!f.exists() || !f.canRead()) {
            throw new Error("Could not load user provided overrides for ClassFiltering from as " + prop + " does not exist or is not readable.");
        }

        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(prop), Charset.defaultCharset()));
            ArrayList<String> patterns = new ArrayList<String>();
            for (String line = br.readLine(); line != null; line = br.readLine()) {
                try {
                    Pattern.compile(line);
                    patterns.add(line);
                } catch (PatternSyntaxException pex) {
                    throw new Error("Error compiling blacklist expressions - '" + line + "' is not a valid regular expression.", pex);
                }
            }
            return patterns;
        } catch (IOException ex) {
            throw new Error("Could not load user provided overrides for ClassFiltering from as "+prop+" does not exist or is not readable.",ex);
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
    private static final class RegExpClassFilter extends ClassFilter {

        /**
         * Any regex that is {@code ^some[.]package[.]name[.].*} or {@code ^some\.package\.name\.*} is really just a
         * {@link String#startsWith(String)} test and we can reduce CPU usage by performing that test explicitly as
         * well as reduce GC pressure.
         */
        private static final Pattern OPTIMIZE1 = Pattern.compile(
                "^\\^(([\\p{L}_$][\\p{L}\\p{N}_$]*(\\.|\\[\\.\\])?)+)\\.\\*$");

        /**
         * Any regex that is {@code ^\Qsome.package.name\E.*} is really just a {@link String#startsWith(String)}
         * test and we can reduce CPU usage by performing that test explicitly as well as reduce GC pressure.
         */
        private static final Pattern OPTIMIZE2 = Pattern.compile("^\\^\\Q[^\\\\]+\\\\E\\.\\*$");

        private final Object[] blacklistPatterns;

        public RegExpClassFilter(List<Pattern> blacklistPatterns) {
            this.blacklistPatterns = blacklistPatterns.toArray(new Pattern[blacklistPatterns.size()]);
        }

        RegExpClassFilter(String[] patterns) {
            blacklistPatterns = new Object[patterns.length];
            for (int i = 0, patternsLength = patterns.length; i < patternsLength; i++) {
                if (OPTIMIZE1.matcher(patterns[i]).matches()) {
                    // this is a simple startsWith test, no need to slow things down with a regex
                    blacklistPatterns[i] = patterns[i].substring(1,patterns[i].length()-2).replace("[.]",".");
                } else  if (OPTIMIZE2.matcher(patterns[i]).matches()) {
                    // this is a simple startsWith test, no need to slow things down with a regex
                    blacklistPatterns[i] = patterns[i].substring(3,patterns[i].length()-4);
                } else {
                    blacklistPatterns[i] = Pattern.compile(patterns[i]);
                }
            }
        }

        @Override
        protected boolean isBlacklisted(String name) {
            for (int i = 0; i < blacklistPatterns.length; i++) {
                Object p = blacklistPatterns[i];
                if (p instanceof Pattern && ((Pattern)p).matcher(name).matches()) {
                    return true;
                } else if (p instanceof String && name.startsWith((String)p)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Report the patterns that it's using to help users verify the use of custom filtering rule
         * and inspect its content at runtime if necessary.
         */
        @Override
        public String toString() {
            return Arrays.toString(blacklistPatterns);
        }
    }
}

/*
    Publicized attack payload:

		ObjectInputStream.readObject()
			PriorityQueue.readObject()
				Comparator.compare() (Proxy)
					ConvertedClosure.invoke()
						MethodClosure.call()
							...
						  		Method.invoke()
									Runtime.exec()


		ObjectInputStream.readObject()
			AnnotationInvocationHandler.readObject()
				Map(Proxy).entrySet()
					AnnotationInvocationHandler.invoke()
						LazyMap.get()
							ChainedTransformer.transform()
								ConstantTransformer.transform()
								InvokerTransformer.transform()
									Method.invoke()
										Class.getMethod()
								InvokerTransformer.transform()
									Method.invoke()
										Runtime.getRuntime()
								InvokerTransformer.transform()
									Method.invoke()
										Runtime.exec()


		ObjectInputStream.readObject()
			PriorityQueue.readObject()
				...
					TransformingComparator.compare()
						InvokerTransformer.transform()
							Method.invoke()
								Runtime.exec()


		ObjectInputStream.readObject()
			SerializableTypeWrapper.MethodInvokeTypeProvider.readObject()
				SerializableTypeWrapper.TypeProvider(Proxy).getType()
					AnnotationInvocationHandler.invoke()
						HashMap.get()
				ReflectionUtils.findMethod()
				SerializableTypeWrapper.TypeProvider(Proxy).getType()
					AnnotationInvocationHandler.invoke()
						HashMap.get()
				ReflectionUtils.invokeMethod()
					Method.invoke()
						Templates(Proxy).newTransformer()
							AutowireUtils.ObjectFactoryDelegatingInvocationHandler.invoke()
								ObjectFactory(Proxy).getObject()
									AnnotationInvocationHandler.invoke()
										HashMap.get()
								Method.invoke()
									TemplatesImpl.newTransformer()
										TemplatesImpl.getTransletInstance()
											TemplatesImpl.defineTransletClasses()
												TemplatesImpl.TransletClassLoader.defineClass()
													Pwner*(Javassist-generated).<static init>
														Runtime.exec()

 */

