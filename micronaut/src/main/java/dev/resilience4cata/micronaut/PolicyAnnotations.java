package dev.resilience4cata.micronaut;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Whether a type declares resilience4cata policies, read from its class file: the annotations have {@code CLASS}
 * retention, so reflection cannot see them. A type declares policies when the type itself or one of its methods
 * carries a policy annotation ({@code @Retry}, {@code @CircuitBreaker} and the others) or a bundle (an annotation
 * meta-annotated with {@code @ResiliencePolicy}). Used to find annotated types that have no generated class: the
 * annotation processor did not run when they were compiled.
 *
 * <p>A minimal class-file reader: the constant pool's UTF-8 entries, then the {@code RuntimeInvisibleAnnotations}
 * and {@code RuntimeVisibleAnnotations} attributes of the class and its methods. A type whose class file cannot be
 * read (none in a native image, or a class generated at run time) declares nothing. Results are cached per type.
 * The same reader exists in the Spring integration; keep both in sync.
 */
final class PolicyAnnotations {

    private static final String PACKAGE = "Ldev/resilience4cata/annotation/";
    private static final Set<String> POLICIES = Set.of(PACKAGE + "Retry;", PACKAGE + "Hedge;",
            PACKAGE + "CircuitBreaker;", PACKAGE + "RateLimiter;", PACKAGE + "AdaptiveLimit;", PACKAGE + "Timeout;",
            PACKAGE + "Bulkhead;", PACKAGE + "Fallback;");
    private static final String BUNDLE = PACKAGE + "ResiliencePolicy;";
    /** Bundles may contain bundles; annotations may annotate each other, so the walk stops here. */
    private static final int MAX_BUNDLE_DEPTH = 8;

    private final Map<Class<?>, Boolean> declares = new ConcurrentHashMap<>();
    private final Map<String, Boolean> bundles = new ConcurrentHashMap<>();

    /** Whether {@code type} or one of its methods carries a policy annotation or a bundle. */
    boolean declaresPolicies(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        if (loader == null || GeneratedClasses.skipped(type.getName())) {
            return false;
        }
        return declares.computeIfAbsent(type, t -> {
            for (String annotation : annotations(loader, t.getName().replace('.', '/'), true)) {
                if (POLICIES.contains(annotation) || isBundle(loader, annotation)) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean isBundle(ClassLoader loader, String descriptor) {
        return isBundle(loader, descriptor, 0);
    }

    /**
     * Whether an annotation is a bundle that brings at least one policy, directly or through another bundle: a
     * bundle with only {@code @PolicyOrder} protects nothing, so the processor generates nothing for it.
     */
    private boolean isBundle(ClassLoader loader, String descriptor, int depth) {
        if (depth > MAX_BUNDLE_DEPTH || descriptor.startsWith(PACKAGE) || descriptor.startsWith("Ljava/")
                || !descriptor.startsWith("L") || !descriptor.endsWith(";")) {
            return false;
        }
        Boolean known = bundles.get(descriptor);
        if (known != null) {
            return known;
        }
        Set<String> meta = annotations(loader, descriptor.substring(1, descriptor.length() - 1), false);
        boolean bundle = false;
        if (meta.contains(BUNDLE)) {
            for (String annotation : meta) {
                if (POLICIES.contains(annotation) || isBundle(loader, annotation, depth + 1)) {
                    bundle = true;
                    break;
                }
            }
        }
        bundles.put(descriptor, bundle);
        return bundle;
    }

    /** The descriptors of the annotations on a class (and on its methods, if asked); empty if it cannot be read. */
    static Set<String> annotations(ClassLoader loader, String internalName, boolean withMethods) {
        try (InputStream stream = loader.getResourceAsStream(internalName + ".class")) {
            return stream == null ? Set.of() : read(new DataInputStream(stream), withMethods);
        } catch (IOException | RuntimeException e) {
            return Set.of(); // unreadable or malformed: claim nothing rather than fail the startup
        }
    }

    private static Set<String> read(DataInputStream in, boolean withMethods) throws IOException {
        in.readInt(); // magic
        in.readUnsignedShort(); // minor version
        in.readUnsignedShort(); // major version
        @Nullable String[] utf8 = constantPool(in);
        in.readUnsignedShort(); // access flags
        in.readUnsignedShort(); // this class
        in.readUnsignedShort(); // super class
        in.skipBytes(2 * in.readUnsignedShort()); // interfaces
        Set<String> found = new HashSet<>();
        members(in, utf8, null); // fields
        members(in, utf8, withMethods ? found : null); // methods
        attributes(in, utf8, found); // the class's own
        return found;
    }

    private static @Nullable String[] constantPool(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        @Nullable String[] utf8 = new String[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.skipBytes(4); // integer, float
                case 5, 6 -> { // long, double: two entries
                    in.skipBytes(8);
                    i++;
                }
                case 7, 8, 16, 19, 20 -> in.skipBytes(2); // class, string, method type, module, package
                case 9, 10, 11, 12, 17, 18 -> in.skipBytes(4); // references, name and type, (invoke)dynamic
                case 15 -> in.skipBytes(3); // method handle
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        return utf8;
    }

    private static void members(DataInputStream in, @Nullable String[] utf8, @Nullable Set<String> found)
            throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipBytes(6); // access flags, name, descriptor
            if (found != null) {
                attributes(in, utf8, found);
            } else {
                skipAttributes(in);
            }
        }
    }

    private static void attributes(DataInputStream in, @Nullable String[] utf8, Set<String> found) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            String name = utf8[in.readUnsignedShort()];
            int length = in.readInt();
            if ("RuntimeInvisibleAnnotations".equals(name) || "RuntimeVisibleAnnotations".equals(name)) {
                int annotations = in.readUnsignedShort();
                for (int a = 0; a < annotations; a++) {
                    String type = utf8[in.readUnsignedShort()];
                    if (type != null) {
                        found.add(type);
                    }
                    skipElementValuePairs(in);
                }
            } else {
                in.skipBytes(length);
            }
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();
            in.skipBytes(in.readInt());
        }
    }

    private static void skipElementValuePairs(DataInputStream in) throws IOException {
        int pairs = in.readUnsignedShort();
        for (int p = 0; p < pairs; p++) {
            in.readUnsignedShort(); // element name
            skipElementValue(in);
        }
    }

    private static void skipElementValue(DataInputStream in) throws IOException {
        int tag = in.readUnsignedByte();
        switch (tag) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> in.skipBytes(2);
            case 'e' -> in.skipBytes(4); // enum: type and constant name
            case '@' -> { // nested annotation
                in.readUnsignedShort();
                skipElementValuePairs(in);
            }
            case '[' -> {
                int values = in.readUnsignedShort();
                for (int v = 0; v < values; v++) {
                    skipElementValue(in);
                }
            }
            default -> throw new IOException("unknown element value tag " + (char) tag);
        }
    }
}
