package com.example.vibefunc;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ReflectionUtils;

import javax.tools.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.*;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@SpringBootApplication
public class VibefuncApplication {

    public static void main(String[] args) {
        SpringApplication.run(VibefuncApplication.class, args);
    }


    @Bean
    ApplicationRunner runner(ChatClient.Builder builder) {
        var ai = builder
                .defaultSystem("""
                        write a valid Java 25 compliant method that implements the signature shown. 
                        do not include an enclosing class. 
                        do not include any explanation of the code. just working, valid, compileable Java method body code.
                        do not include it in markdown code delimiters or anything. imagine that im going to take the response and
                        pipe it right into the java compiler.  
                        """)
                .build();
        return _ -> {
            var result = generateFor(ai, Greeting.class);
            var clzz = result.className();
            var code = result.code();
            var classes = InProcessJavaCompiler.compile(clzz, code, List.of());
            var loader = new BytesClassLoader(Thread.currentThread().getContextClassLoader());
            for (var k : classes.keySet()) {
                IO.println(k);
                var bytes = classes.get(k);
                var clzzImpl = loader.define(k, bytes);
                var instance = clzzImpl.getDeclaredConstructor().newInstance();
                var invocationResult = clzzImpl
                        .getMethod("greet", String.class).invoke(instance, "Hedge");
                IO.println(invocationResult);
            }
            //var helloClass = loader.define("Greeting", classes.get("com.example.Hello"));

//            var enhancer = new net.sf.cglib.proxy.Enhancer();
//            enhancer.setSuperclass(helloClass);
//            enhancer.setCallback((net.sf.cglib.proxy.MethodInterceptor) (obj, method, args, proxy) -> {
//                if (method.getName().equals("greet")) return ("[proxied] " + proxy.invokeSuper(obj, args));
//                return proxy.invokeSuper(obj, args);
//            });
//            Object proxied = enhancer.create();

        };
    }

    private static Set<Method> methodSet(Class<?> clzz) {
        var set = new HashSet<Method>();
        var methods = ReflectionUtils.getDeclaredMethods(clzz);
        for (var method : methods) {
//            IO.println(method);
            if (method.getAnnotationsByType(Func.class).length > 0) {
                set.add(method);
            }
        }
        return set;
    }

    record CompilationUnit(String code, String className) {
    }

    static CompilationUnit generateFor(ChatClient ai, Class<?> interfaceClass) {
        var newCode = new StringBuilder();
        for (var funcMethod : methodSet(interfaceClass)) {
            var funcAnnotation = funcMethod.getAnnotation(Func.class);
            var prompt = funcAnnotation.value();
            var implementedMethodCode = " " + ai.prompt().user(prompt).call().content();
            newCode.append(implementedMethodCode).append(System.lineSeparator());
        }
        newCode = new StringBuilder("public  class " + interfaceClass.getSimpleName()  +// + "Impl " +

//                "implements " + interfaceClass.getSimpleName() +
                " {" + newCode + "}");
        var code = newCode.toString();
        return new CompilationUnit(code, interfaceClass.getSimpleName());

    }
}


interface Greeting {

    @Func("""
             return a greeting String 
            
             >>> greet("Alice") 
             'Hello, Alice!' 
             >>> greet("ni hao")
             'Hello, ni hao '
            """)
    String greet(String name);

}

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@interface Func {
    String value() default "";
}

// hope and pray
class BytesClassLoader extends ClassLoader {

    Class<?> define(String name, byte[] bytes) {
        return defineClass(name, bytes, 0, bytes.length);
    }

    BytesClassLoader(ClassLoader parent) {
        super(parent);
    }
}

class InProcessJavaCompiler {


    // hold compiled .class bytes in memory
    static class MemFile extends SimpleJavaFileObject {

        private final String src;           // for SOURCE

        private final ByteArrayOutputStream baos = new ByteArrayOutputStream(); // for CLASS

        MemFile(String className, String source) {
            super(URI.create("mem:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
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
        public JavaFileObject getJavaFileForOutput(Location loc, String className,
                                                   JavaFileObject.Kind kind, FileObject src) {
            var out = new MemFile(className, kind);
            classes.put(className, out);
            return out;
        }

        Map<String, byte[]> output() {
            Map<String, byte[]> out = new HashMap<>();
            classes.forEach((n, f) -> out.put(n, f.bytes()));
            return out;
        }
    }

    public static Map<String, byte[]> compile(String className, String source,
                                              List<String> options) {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager std = jc.getStandardFileManager(diags, null, null)) {
            MemManager mem = new MemManager(std);
            JavaFileObject src = new MemFile(className, source);
            JavaCompiler.CompilationTask task =
                    jc.getTask(null, mem, diags, options, null, List.of(src));
            boolean ok = task.call();
            if (!ok) throw new IllegalStateException(diags.getDiagnostics().toString());
            return mem.output(); // class name -> .class bytes
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    // optional: write .class files to disk
    public static void writeToDir(Map<String, byte[]> classes, Path outDir) throws IOException {
        for (var e : classes.entrySet()) {
            Path p = outDir.resolve(e.getKey().replace('.', '/') + ".class");
            Files.createDirectories(p.getParent());
            Files.write(p, e.getValue());
        }
    }
}
