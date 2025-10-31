package vibesafe4j;


class BytesClassLoader extends ClassLoader {

    BytesClassLoader(ClassLoader parent) {
        super(parent);
    }

    Class<?> define(String name, byte[] bytes) {
        return defineClass(name, bytes, 0, bytes.length);
    }
}
