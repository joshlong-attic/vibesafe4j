package vibesafe4j;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
