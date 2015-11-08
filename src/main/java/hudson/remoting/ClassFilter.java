package hudson.remoting;

import javax.annotation.Nullable;

/**
 * Restricts what classes can be received through remoting.
 *
 * @author Kohsuke Kawaguchi
 * @since 2.53
 */
public abstract class ClassFilter {
    protected boolean isBlacklisted(String name) {
        return false;
    }

    protected boolean isBlacklisted(Class c) {
        return false;
    }

    /**
     * Wraps a given {@link ClassLoader} into one that does filtering.
     */
    public ClassLoader decorate(final @Nullable ClassLoader actual) {
        return new ClassLoader(actual) {// see MaskingClassLoader in Jenkins for inspiration
            @Override
            public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (isBlacklisted(name)) throw new SecurityException("Rejected: " +name);
                Class<?> c = super.loadClass(name,resolve);
                if (isBlacklisted(c)) throw new SecurityException("Rejected: "+ name);
                return c;
            }
        };
    }


    /**
     * A set of sensible default filtering rules to apply,
     * unless the context guarantees the trust between two channels.
     */
    public static final ClassFilter DEFAULT = new ClassFilter() {
        @Override
        protected boolean isBlacklisted(String name) {
            // these are coming from libraries, so protecting it by name is better as
            // some plugins might be bundling them and choosing to mask ones from core.
            if (name.startsWith("org.codehaus.groovy.runtime."))
                return true;    // ConvertedClosure is named in exploit
            if (name.startsWith("org.apache.commons.collections.functors."))
                return true;    // InvokerTransformer, InstantiateFactory, InstantiateTransformer are particularly scary

            // this package can appear in ordinary xalan.jar or com.sun.org.apache.xalan
            // the target is trax.TemplatesImpl
            if (name.contains("org.apache.xalan"))
                return true;
            return false;
        }
    };

    /**
     * No filtering whatsoever.
     */
    public static final ClassFilter NONE = new ClassFilter() {
    };
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

