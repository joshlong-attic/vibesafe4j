package vibesafe4j;

import org.springframework.util.ReflectionUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Generates type-safe implementations of interfaces given AI prompts.
 *
 * @author Josh Long
 * @author Diwank Singh Tomer
 */
public class Vibesafe4j {

	private final static String DEFAULT_PROMPT = """

			write a valid Java 25 compliant method that implements the signature shown.
			do not include an enclosing class.
			do not include any explanation of the code. just working, valid, compileable Java method body code.
			do not include it in markdown code delimiters or anything. imagine that im going to take the response and
			pipe it right into the java compiler.

			""";

	@SuppressWarnings("unchecked")
	public static <T> T build(Function<String, String> aiCallback, Class<T> clzz)
			throws InvocationTargetException, InstantiationException, IllegalAccessException {
		var cu = sourceFor(aiCallback, clzz);
		var clzzInstance = classFor(cu);
		return (T) clzzInstance.getConstructors()[0].newInstance();
	}

	static Class<?> classFor(CompilationUnit result) {
		var javaVersion = System.getProperty("java.version");
		var clzz = result.className();
		var code = result.code();
		var classes = InProcessJavaCompiler.compile(clzz, code,
				List.of("-source", javaVersion, "-target", javaVersion));
		var loader = new BytesClassLoader(ClassLoader.getSystemClassLoader());
		for (var k : classes.keySet()) {
			var bytes = classes.get(k);
			return loader.define(k, bytes);
		}
		throw new RuntimeException("Unable to load class " + clzz);

	}

	static CompilationUnit sourceFor(Function<String, String> callback, Class<?> interfaceClass) {
		var newClassName = interfaceClass.getSimpleName() + "Impl";
		var newCode = new StringBuilder();
		for (var funcMethod : funcyMethods(interfaceClass)) {
			var funcAnnotation = funcMethod.getAnnotation(Func.class);
			var prompt = DEFAULT_PROMPT + funcAnnotation.value();
			var implementedMethodCode = callback.apply(prompt);
			newCode.append(implementedMethodCode).append(System.lineSeparator());
		}
		newCode = new StringBuilder(" package " + interfaceClass.getPackage().getName() + ".gen" + " ; "
				+ System.lineSeparator() + System.lineSeparator() + "  public class " + newClassName + " implements "
				+ interfaceClass.getName() + System.lineSeparator() + " { " + newCode + " }");
		var code = newCode.toString();
		return new CompilationUnit(code, newClassName);

	}

	public record CompilationUnit(String code, String className) {
	}

	private static Set<Method> funcyMethods(Class<?> clzz) {
		var set = new HashSet<Method>();
		var methods = ReflectionUtils.getDeclaredMethods(clzz);
		for (var method : methods) {
			// IO.println(method);
			if (method.getAnnotationsByType(Func.class).length > 0) {
				set.add(method);
			}
		}
		return set;
	}

}
