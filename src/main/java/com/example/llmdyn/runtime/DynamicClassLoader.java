
package com.example.llmdyn.runtime;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

public class DynamicClassLoader extends URLClassLoader {
    public DynamicClassLoader(Path classDir, ClassLoader parent) throws IOException {
        super(new URL[]{classDir.toUri().toURL()}, parent);
    }
}
