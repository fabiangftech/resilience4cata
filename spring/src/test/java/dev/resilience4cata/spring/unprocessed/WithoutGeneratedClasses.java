package dev.resilience4cata.spring.unprocessed;

import dev.resilience4cata.Resilient;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;

/**
 * Loads the classes of this package itself and hides their generated {@code $Resilient} classes, which the
 * processor wrote when the tests were compiled: to the Spring integration, these types then look like types compiled
 * without the processor. Everything else comes from the parent.
 */
public final class WithoutGeneratedClasses extends ClassLoader {

    private static final String PACKAGE = WithoutGeneratedClasses.class.getPackageName() + ".";

    public WithoutGeneratedClasses() {
        super(WithoutGeneratedClasses.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!name.startsWith(PACKAGE) || name.equals(WithoutGeneratedClasses.class.getName())) {
            return super.loadClass(name, resolve);
        }
        if (name.endsWith(Resilient.GENERATED_SUFFIX)) {
            throw new ClassNotFoundException(name);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                byte[] bytes = bytes(name.replace('.', '/') + ".class");
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            return loaded;
        }
    }

    @Override
    public URL getResource(String name) {
        return name.endsWith(Resilient.GENERATED_SUFFIX + ".class") ? null : super.getResource(name);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return name.endsWith(Resilient.GENERATED_SUFFIX + ".class") ? null : super.getResourceAsStream(name);
    }

    private byte[] bytes(String resource) throws ClassNotFoundException {
        try (InputStream in = getParent().getResourceAsStream(resource)) {
            if (in == null) {
                throw new ClassNotFoundException(resource);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
