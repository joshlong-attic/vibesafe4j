package vibesafe4j;

import org.springframework.util.ReflectionUtils;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;

/**
 * Generates type-safe implementations of interfaces given AI prompts.
 *
 * @author Josh Long
 * @author Diwank Singh Tomer
 */
public abstract class Vibesafe4j {

	private static final String CLASS_TEMPLATE = """

			package %s ;

			public class %s implements %s {

			    %s

			}

			""";

	private final static String DEFAULT_PROMPT = """

			write a valid Java 25 compliant method that implements the signature shown.
			do not include an enclosing class.
			do not include any explanation of the code. just working, valid, compileable Java method body code.
			do not include it in markdown code delimiters or anything. imagine that im going to take the response and
			pipe it right into the java compiler.

			""";

	/**
	 * Most users will start here.
	 * @param aiCallback how will the proxy invoke an LLM model? It's trivial to plugin
	 * whatever API you want here.
	 * @param clzz the class whose shape we want the resulting proxy to assume.
	 * @param <T> the type of the resulting instance.
	 * @return a valid instance.
	 * @throws InvocationTargetException , InstantiationException, IllegalAccessException
	 * - lots of things can go wrong in the world of reflection and compilers.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T build(Function<String, String> aiCallback, Class<T> clzz)
			throws InvocationTargetException, InstantiationException, IllegalAccessException, ClassNotFoundException {
		var cu = sourceFor(aiCallback, clzz);
		var clzzInstance = classFor(cu);
		return (T) clzzInstance.getConstructors()[0].newInstance();
	}

	private static Class<?> defineIntoInterfaceLoader(Class<?> anchor, Map<String, byte[]> compiled, String mainFqcn)
			throws Throwable {

		var pkgLookup = MethodHandles.privateLookupIn(anchor, MethodHandles.lookup());
		var defined = new HashMap<String, Class<?>>();
		var progressed = false;
		do {
			progressed = false;
			for (var entry : compiled.entrySet()) {
				var name = entry.getKey();
				if (defined.containsKey(name))
					continue;
				var bytes = entry.getValue();
				try {
					// If already defined (e.g., by a prior call), just resolve it
					var already = Class.forName(name, false, anchor.getClassLoader());
					defined.put(name, already);
					progressed = true;
					continue;
				}
				catch (ClassNotFoundException ignore) {
					// fall through to define
				}
				try {
					var definedClz = pkgLookup.defineClass(bytes); // defines in same
					// loader & package as
					// anchor
					defined.put(name, definedClz);
					progressed = true;
				}
				catch (NoClassDefFoundError e) {
					// A dependency not yet defined; we’ll retry next pass.
				}
			}
		} //
		while (defined.size() < compiled.size() && progressed);

		if (!defined.containsKey(mainFqcn)) {
			return Class.forName(mainFqcn, true, anchor.getClassLoader());
		}
		return defined.get(mainFqcn);
	}

	public static Class<?> classFor(CompilationUnit result) {
		var feature = Runtime.version().feature();
		var javaFeature = Integer.toString(feature);

		var ifaceClazz = result.interfaceClass();
		var mainFqcn = ifaceClazz.getPackageName() + "." + result.className();
		var code = result.code();

		var options = List.of(
				//
				"-classpath", System.getProperty("java.class.path"),
				//
				"-source", javaFeature,
				//
				"-target", javaFeature);
		var classes = InProcessJavaCompiler.compile(mainFqcn, code, options);
		try {
			return defineIntoInterfaceLoader(ifaceClazz, classes, mainFqcn);
		} //
		catch (Throwable t) {
			throw new IllegalStateException("Failed to define generated classes into the interface's loader; "
					+ "if your interface isn't public, cross-loader access will fail. "
					+ "Consider making the interface public or keep this define-in-place path.", t);
		}
	}

	public static CompilationUnit sourceFor(Function<String, String> callback, Class<?> interfaceClass) {
		var nl2 = System.lineSeparator() + System.lineSeparator();
		var newClassName = interfaceClass.getSimpleName() + "Impl";
		var newCode = new StringBuilder();
		for (var funcMethod : eligibleMethods(interfaceClass)) {
			var funcAnnotation = funcMethod.getAnnotation(Func.class);
			var prompt = DEFAULT_PROMPT + funcAnnotation.value();
			var implementedMethodCode = callback.apply(prompt);
			if (!implementedMethodCode.contains("public"))
				implementedMethodCode = " public " + implementedMethodCode;
			newCode.append(nl2).append(implementedMethodCode).append(nl2);
		}
		newCode = new StringBuilder(CLASS_TEMPLATE.formatted(interfaceClass.getPackage().getName(), newClassName,
				interfaceClass.getSimpleName(), newCode.toString()));
		return new CompilationUnit(newCode.toString(), interfaceClass, newClassName);
	}

	public record CompilationUnit(String code, Class<?> interfaceClass, String className) {
	}

	private static Set<Method> eligibleMethods(Class<?> clzz) {
		var set = new HashSet<Method>();
		var methods = ReflectionUtils.getDeclaredMethods(clzz);
		for (var method : methods)
			if (method.getAnnotationsByType(Func.class).length > 0)
				set.add(method);
		return set;
	}

}
