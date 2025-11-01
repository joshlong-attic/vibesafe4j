package vibesafe4j;

import org.springframework.util.ReflectionUtils;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Generates type-safe implementations of interfaces given AI prompts.
 *
 * @author Josh Long
 * @author Diwank Singh Tomer
 */
public abstract class Vibesafe4j {

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

	static Class<?> defineIntoInterfaceLoader(Class<?> anchor, Map<String, byte[]> compiled, String mainFqcn)
			throws Throwable {
		// A private lookup on the interface lets us define classes in the SAME loader &
		// package
		var pkgLookup = MethodHandles.privateLookupIn(anchor, MethodHandles.lookup());

		// Define all compiled classes. Order generally doesn't matter, but define
		// dependencies as encountered.

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

		var classes = InProcessJavaCompiler.compile(mainFqcn, code, List.of("-classpath",
				System.getProperty("java.class.path"), "-source", javaFeature, "-target", javaFeature));

		try {
			return defineIntoInterfaceLoader(ifaceClazz, classes, mainFqcn);
		}
		catch (Throwable t) {
			var iae = new IllegalStateException("Failed to define generated classes into the interface's loader; "
					+ "if your interface isn't public, cross-loader access will fail. "
					+ "Consider making the interface public or keep this define-in-place path.", t);
			throw iae;
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

	private static final String CLASS_TEMPLATE = """

			package %s ;

			public class %s implements %s {

			    %s

			}

			""";

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

class InProcessJavaCompiler {

	public static Map<String, byte[]> compile(String classNameFqcn, String source, List<String> options) {
		var jc = ToolProvider.getSystemJavaCompiler();
		var diags = new DiagnosticCollector<JavaFileObject>();
		try (var std = jc.getStandardFileManager(diags, null, null)) {
			var mem = new MemManager(std);
			var src = new MemFile(classNameFqcn, source);
			var task = jc.getTask(null, mem, diags, options, null, List.of(src));
			var ok = task.call();
			if (!ok)
				throw new IllegalStateException(diags.getDiagnostics().toString());
			return mem.output();
		}
		catch (IOException ioe) {
			throw new UncheckedIOException(ioe);
		}
	}

	// hold compiled .class bytes in memory
	static class MemFile extends SimpleJavaFileObject {

		private final String src;

		private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

		MemFile(String className, String source) {
			super(URI.create("mem:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
			this.src = source;
		}

		MemFile(String className, Kind kind) {
			super(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind);
			this.src = null;
		}

		@Override
		public CharSequence getCharContent(boolean ignore) {
			return src;
		}

		@Override
		public OutputStream openOutputStream() {
			return baos;
		}

		byte[] bytes() {
			return baos.toByteArray();
		}

	}

	static class MemManager extends ForwardingJavaFileManager<JavaFileManager> {

		private final Map<String, MemFile> classes = new ConcurrentHashMap<>();

		MemManager(JavaFileManager fileManager) {
			super(fileManager);
		}

		@Override
		public JavaFileObject getJavaFileForOutput(Location loc, String className, JavaFileObject.Kind kind,
				FileObject src) {
			var out = new MemFile(className, kind);
			classes.put(className, out);
			return out;
		}

		Map<String, byte[]> output() {
			var out = new HashMap<String, byte[]>();
			classes.forEach((n, f) -> out.put(n, f.bytes()));
			return out;
		}

	}

}
