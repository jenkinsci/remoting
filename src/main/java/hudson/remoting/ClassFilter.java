package hudson.remoting;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/**
 * Restricts what classes can be received through remoting.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.53
 */
public abstract class ClassFilter {

    static {
        // for backwards compatibility for people that use ClassFilter.DEFAULT
        getDefaultFilter();
    }
    /** 
     * Property to set to <b>override<b> the blacklist used by {{@link #DEFAULT} with a different set.
     * The location should point to a a file containing regular expressions (one per line) of classes to blacklist.
     * If this property is set but the file can not be read the default blacklist will be used. 
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

	/**
	 * @deprecated use {@link #getDefaultFilter()}
	 */
	@Deprecated
	public static /*almost final */ ClassFilter DEFAULT;

    /**
     * No filtering whatsoever.
     */
    private static final ClassFilter NONE = new ClassFilter() {
    };

    /**
     * The default filtering rules to apply, unless the context guarantees the trust between two channels. The defaults
     * values provide for user specified overrides - see {@link #FILE_OVERRIDE_LOCATION_PROPERTY}.
     */
    public static synchronized final ClassFilter getDefaultFilter() {
        if (DEFAULT == null) {
            List<Pattern> patternOverride = loadPatternOverride();
            if (patternOverride != null) {
                LOGGER.log(Level.INFO, "Using user specified overrides for class blacklisting");
                DEFAULT = new RegExpClassFilter(patternOverride);
            }
            else {
                LOGGER.log(Level.INFO, "Using default in built class blacklisting");
                DEFAULT = new RegExpClassFilter(Arrays.asList(Pattern.compile("^org\\.codehaus\\.groovy\\.runtime\\..*"), 
                                                              Pattern.compile("^org\\.apache\\.commons\\.collections\\.functors\\..*"),
                                                              Pattern.compile(".*org\\.apache\\.xalan.*")
                                                ));
            }
        }
        return DEFAULT;
    }


    /**
     * No filtering whatsoever.
     */
    public static final ClassFilter getNOOPFilter() {
        return NONE;
    }

    @CheckForNull
    private static final List<Pattern> loadPatternOverride() {
        String prop = System.getProperty(FILE_OVERRIDE_LOCATION_PROPERTY);
        if (prop != null) {
            LOGGER.log(Level.INFO, "Attempting to load user provided overrides for ClassFiltering from ''{0}''.", prop);
            File f = new File(prop);
            if (f.exists() && f.canRead()) {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new FileReader(prop));
                    ArrayList<Pattern> patterns = new ArrayList<Pattern>();
                    for (String line = br.readLine(); line != null; line = br.readLine()) {
                        try {
                            patterns.add(Pattern.compile(line));
                        } catch (PatternSyntaxException pex) {
                            LOGGER.log(Level.WARNING, "Error compiling blacklist expressions - '" + line
                                                            + "' is not a valid regular expression.", pex);
                            // we could continue the rest of the of the lines - but it is better to be all or nothing
                            // not a halfway house.
                            return null;
                        }
                    }
                    return patterns;
                } catch (IOException ex) {
                    LOGGER.log(Level.WARNING,
                               "Could not load user provided overrides for ClassFiltering from as file does not exist or is not readable.",
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
            } else {
                LOGGER.log(Level.WARNING,
                           "Could not load user provided overrides for ClassFiltering from as file does not exist or is not readable.");
            }
        }
        return null;
    }

    /**
     * A class that uses a given set of regular expression patterns to determine if the class is blacklisted.
     */
    private static final class RegExpClassFilter extends ClassFilter {

        private final List<Pattern> blacklistPatterns;

        public RegExpClassFilter(List<Pattern> blacklistPatterns) {
            this.blacklistPatterns = blacklistPatterns;
        }

        @Override
        protected boolean isBlacklisted(String name) {
            for (Pattern p : blacklistPatterns) {
                if (p.matcher(name).matches()) {
                    return true;
                }
            }
            return false;
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

