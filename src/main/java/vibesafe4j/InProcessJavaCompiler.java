package vibesafe4j;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class InProcessJavaCompiler {

	public static Map<String, byte[]> compile(String className, String source, List<String> options) {
		var jc = ToolProvider.getSystemJavaCompiler();
		var diags = new DiagnosticCollector<>();
		try (var std = jc.getStandardFileManager(diags, null, null)) {
			var mem = new MemManager(std);
			var src = new MemFile(className, source);
			var task = jc.getTask(null, mem, diags, options, null, List.of(src));
			var ok = task.call();

			if (!ok)
				throw new IllegalStateException(diags.getDiagnostics().toString());

			return mem.output(); // class name -> .class bytes
		}
		catch (IOException ioe) {
			throw new UncheckedIOException(ioe);
		}
	}
	/*
	 *
	 * // optional: write .class files to disk static void writeToDir(Map<String, byte[]>
	 * classes, Path outDir) throws IOException { for (var e : classes.entrySet()) { var p
	 * = outDir.resolve(e.getKey().replace('.', '/') + ".class");
	 * Files.createDirectories(p.getParent()); Files.write(p, e.getValue()); } }
	 */

	// hold compiled .class bytes in memory
	static class MemFile extends SimpleJavaFileObject {

		private final String src; // for SOURCE

		private final ByteArrayOutputStream baos = new ByteArrayOutputStream(); // for
																				// CLASS

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

		private final Map<String, MemFile> classes = new HashMap<>();

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
