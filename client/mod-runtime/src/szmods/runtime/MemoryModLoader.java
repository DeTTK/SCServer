package szmods.runtime;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;

final class MemoryModLoader extends URLClassLoader {
    private final Map<String, byte[]> entries = new HashMap<>();
    MemoryModLoader(byte[] archive, ClassLoader parent) throws IOException {
        super(new URL[0], parent);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            long total = 0;
            for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
                if (e.isDirectory()) continue;
                byte[] data = zip.readNBytes(32 * 1024 * 1024 + 1);
                if (data.length > 32 * 1024 * 1024 || (total += data.length) > 128 * 1024 * 1024)
                    throw new IOException("Mod too large");
                entries.put(e.getName(), data);
            }
        }
    }
    @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] b = entries.get(name.replace('.', '/') + ".class");
        if (b == null) throw new ClassNotFoundException(name);
        return defineClass(name, b, 0, b.length);
    }
    @Override public URL findResource(String name) {
        byte[] data = entries.get(name);
        if (data == null) return null;
        try { return new URL(null, "memory-mod:/" + name, new URLStreamHandler() {
            protected URLConnection openConnection(URL url) {
                return new URLConnection(url) {
                    public void connect() {}
                    public InputStream getInputStream() { return new ByteArrayInputStream(data); }
                };
            }
        }); } catch (MalformedURLException e) { throw new IllegalArgumentException(e); }
    }
    @Override public Enumeration<URL> findResources(String name) {
        URL url = findResource(name);
        return Collections.enumeration(url == null ? List.of() : List.of(url));
    }
    @Override public void close() throws IOException { entries.clear(); super.close(); }
}
