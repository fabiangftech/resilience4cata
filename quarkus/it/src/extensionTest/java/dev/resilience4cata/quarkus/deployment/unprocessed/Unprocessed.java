package dev.resilience4cata.quarkus.deployment.unprocessed;

import org.jboss.shrinkwrap.api.asset.ClassLoaderAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

/**
 * Adds classes to a test application without their generated {@code $Resilient}, as if the processor had not run.
 * ShrinkWrap's {@code addClasses} also adds every class whose name starts with {@code Outer$}, which includes the
 * generated class, so each class file is added as a plain asset instead.
 */
public final class Unprocessed {

    private Unprocessed() {
    }

    public static JavaArchive add(JavaArchive jar, Class<?>... types) {
        for (Class<?> type : types) {
            String path = type.getName().replace('.', '/') + ".class";
            jar.add(new ClassLoaderAsset(path, type.getClassLoader()), path);
        }
        return jar;
    }
}
