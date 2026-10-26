package dev.resilience4cata.quarkus.deployment;

import io.quarkus.builder.item.SimpleBuildItem;
import java.util.Map;
import org.jboss.jandex.ClassInfo;

/**
 * The classes the annotation processor generated, found in the application index: the wrapper {@code I$Resilient}
 * of each annotated interface {@code I}, and the subclass {@code C$Resilient} of each annotated class {@code C},
 * keyed by the annotated type.
 */
public final class GeneratedTypesBuildItem extends SimpleBuildItem {

    private final Map<String, ClassInfo> wrappers;
    private final Map<String, ClassInfo> subclasses;

    GeneratedTypesBuildItem(Map<String, ClassInfo> wrappers, Map<String, ClassInfo> subclasses) {
        this.wrappers = Map.copyOf(wrappers);
        this.subclasses = Map.copyOf(subclasses);
    }

    /** Annotated interface name → its generated wrapper. */
    Map<String, ClassInfo> wrappers() {
        return wrappers;
    }

    /** Annotated class name → its generated subclass. */
    Map<String, ClassInfo> subclasses() {
        return subclasses;
    }

    /** Whether {@code name} is a class the processor generated. */
    boolean isGenerated(String name) {
        return wrappers.values().stream().anyMatch(c -> c.name().toString().equals(name))
                || subclasses.values().stream().anyMatch(c -> c.name().toString().equals(name));
    }
}
