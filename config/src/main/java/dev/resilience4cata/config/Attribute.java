package dev.resilience4cata.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One configurable attribute of a policy: its key name, how its text value is parsed, and how the
 * parsed value is set on the policy's config builder.
 *
 * @param <B> the config builder type
 */
final class Attribute<B> {

    /** Value types. */
    enum Kind {
        BOOLEAN("true or false"),
        INT("an integer"),
        FLOAT("a number"),
        DOUBLE("a number"),
        DURATION("a duration"),
        ENUM("one of the listed values"),
        CLASSES("a comma-separated list of exception class names");

        final String description;

        Kind(String description) {
            this.description = description;
        }
    }

    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
    private static final List<String> COMMON_PACKAGES = List.of(
            "java.lang.", "java.io.", "java.net.", "java.util.", "java.util.concurrent.", "java.net.http.",
            "java.sql.", "java.time.", "dev.resilience4cata.");

    final String name;
    /** Name of the config record component, as it appears in validation messages. */
    final String field;
    final Kind kind;
    private final @Nullable Class<? extends Enum<?>> enumType;
    private final BiConsumer<B, Object> setter;

    private Attribute(String name, String field, Kind kind, @Nullable Class<? extends Enum<?>> enumType,
            BiConsumer<B, Object> setter) {
        this.name = name;
        this.field = field;
        this.kind = kind;
        this.enumType = enumType;
        this.setter = setter;
    }

    static <B> Attribute<B> bool(String name, BiConsumer<B, Boolean> setter) {
        return new Attribute<>(name, name, Kind.BOOLEAN, null, (b, v) -> setter.accept(b, (Boolean) v));
    }

    static <B> Attribute<B> integer(String name, BiConsumer<B, Integer> setter) {
        return new Attribute<>(name, name, Kind.INT, null, (b, v) -> setter.accept(b, (Integer) v));
    }

    static <B> Attribute<B> decimal(String name, BiConsumer<B, Float> setter) {
        return new Attribute<>(name, name, Kind.FLOAT, null, (b, v) -> setter.accept(b, (Float) v));
    }

    static <B> Attribute<B> real(String name, BiConsumer<B, Double> setter) {
        return new Attribute<>(name, name, Kind.DOUBLE, null, (b, v) -> setter.accept(b, (Double) v));
    }

    static <B> Attribute<B> duration(String name, BiConsumer<B, Duration> setter) {
        return duration(name, name, setter);
    }

    static <B> Attribute<B> duration(String name, String field, BiConsumer<B, Duration> setter) {
        return new Attribute<>(name, field, Kind.DURATION, null, (b, v) -> setter.accept(b, (Duration) v));
    }

    static <B, E extends Enum<E>> Attribute<B> enumeration(String name, Class<E> type, BiConsumer<B, E> setter) {
        return new Attribute<>(name, name, Kind.ENUM, type, (b, v) -> setter.accept(b, type.cast(v)));
    }

    static <B> Attribute<B> classes(String name, BiConsumer<B, List<Class<? extends Throwable>>> setter) {
        return new Attribute<>(name, name, Kind.CLASSES, null, (b, v) -> setter.accept(b, castClasses(v)));
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends Throwable>> castClasses(Object value) {
        return (List<Class<? extends Throwable>>) value;
    }

    void set(B builder, Object value) {
        setter.accept(builder, value);
    }

    /**
     * Parses a (trimmed) text value.
     *
     * @param origin where the value comes from, used in messages
     * @throws ProblemException if the value is invalid
     */
    Object parse(String text, String origin, ClassLoader loader) {
        switch (kind) {
            case BOOLEAN -> {
                if (text.equalsIgnoreCase("true")) {
                    return Boolean.TRUE;
                }
                if (text.equalsIgnoreCase("false")) {
                    return Boolean.FALSE;
                }
                throw invalid(text, origin, "expected true or false");
            }
            case INT -> {
                try {
                    return Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    throw invalid(text, origin, "expected an integer");
                }
            }
            case FLOAT, DOUBLE -> {
                if (!DECIMAL.matcher(text).matches()) {
                    throw invalid(text, origin, "expected a decimal number such as 50 or 2.5");
                }
                if (kind == Kind.FLOAT) {
                    float value = Float.parseFloat(text);
                    if (Float.isInfinite(value)) {
                        throw invalid(text, origin, "too large");
                    }
                    return value;
                }
                double value = Double.parseDouble(text);
                if (Double.isInfinite(value)) {
                    throw invalid(text, origin, "too large");
                }
                return value;
            }
            case DURATION -> {
                try {
                    return Durations.parse(text);
                } catch (IllegalArgumentException e) {
                    throw new ProblemException(ProblemException.INVALID_VALUE, origin + " = \"" + text
                            + "\" is not a valid duration: " + e.getMessage() + ". Use " + Durations.GRAMMAR + ".");
                }
            }
            case ENUM -> {
                Class<? extends Enum<?>> type = enumType;
                assert type != null;
                List<String> names = new ArrayList<>();
                for (Enum<?> constant : type.getEnumConstants()) {
                    if (Suggestions.normalize(constant.name()).equals(Suggestions.normalize(text))) {
                        return constant;
                    }
                    names.add(constant.name().toLowerCase(Locale.ROOT));
                }
                String closest = Suggestions.closest(text, names);
                throw invalid(text, origin, "expected one of " + String.join(", ", names)
                        + (closest == null ? "" : ". Did you mean '" + closest + "'?"));
            }
            default -> {
                return parseClasses(text, origin, loader);
            }
        }
    }

    private List<Class<? extends Throwable>> parseClasses(String text, String origin, ClassLoader loader) {
        List<Class<? extends Throwable>> classes = new ArrayList<>();
        if (text.isEmpty()) {
            return List.of();
        }
        for (String part : text.split(",", -1)) {
            String className = part.strip();
            if (className.isEmpty()) {
                throw invalid(text, origin, "empty class name in the list; separate class names with single commas");
            }
            Class<?> type = load(className, loader);
            if (type == null) {
                String hint = "";
                if (className.indexOf('.') < 0) {
                    for (String pkg : COMMON_PACKAGES) {
                        Class<?> candidate = load(pkg + className, loader);
                        if (candidate != null && Throwable.class.isAssignableFrom(candidate)) {
                            hint = " Did you mean '" + candidate.getName() + "'?";
                            break;
                        }
                    }
                    if (hint.isEmpty()) {
                        hint = " Use the fully qualified name, for example 'java.io.IOException'.";
                    }
                }
                throw new ProblemException(ProblemException.UNKNOWN_CLASS, origin + ": class '" + className
                        + "' not found by the class loader " + loader + "." + hint);
            }
            if (!Throwable.class.isAssignableFrom(type)) {
                throw new ProblemException(ProblemException.UNKNOWN_CLASS, origin + ": '" + type.getName()
                        + "' is not an exception (it does not extend java.lang.Throwable)");
            }
            classes.add(type.asSubclass(Throwable.class));
        }
        return List.copyOf(classes);
    }

    /** Loads a class by binary name, also accepting canonical names of nested classes ({@code a.B.C}). */
    private static @Nullable Class<?> load(String name, ClassLoader loader) {
        String candidate = name;
        while (true) {
            try {
                return Class.forName(candidate, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                int dot = candidate.lastIndexOf('.');
                if (dot < 0) {
                    return null;
                }
                candidate = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1);
            }
        }
    }

    private static ProblemException invalid(String text, String origin, String expected) {
        return new ProblemException(ProblemException.INVALID_VALUE,
                origin + " has an invalid value \"" + text + "\": " + expected);
    }
}
