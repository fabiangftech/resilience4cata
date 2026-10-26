package dev.resilience4cata.processor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleAnnotationValueVisitor14;

/**
 * Prints the declaration annotations of an element as valid Java source, so that the generated subclass can
 * repeat them on the constructors it mirrors (for dependency injection: {@code @Inject}, {@code @Qualifier("x")},
 * {@code @Value("${x}")}...). Only annotations that survive compilation (retention {@code CLASS} or
 * {@code RUNTIME}) and that are applicable to the target are kept; type-use annotations are not declaration
 * annotations and are left to {@link TypePrinter}, which drops them.
 */
final class AnnotationPrinter {

    private static final String CONSTRUCTOR_PROPERTIES = "java.beans.ConstructorProperties";

    private AnnotationPrinter() {
    }

    /**
     * The annotations of {@code element} that apply to {@code target}, each followed by {@code separator}.
     * {@code @ConstructorProperties} gets {@code leadingName} first, for the parameter the generated constructor
     * adds in front of the original ones; {@code leadingName} is ignored for every other annotation.
     */
    static String annotations(Element element, ElementType target, String separator, String leadingName) {
        StringBuilder out = new StringBuilder();
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            if (!copied(annotation, target)) {
                continue;
            }
            String name = ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString();
            if (name.equals(CONSTRUCTOR_PROPERTIES)) {
                out.append(constructorProperties(annotation, leadingName));
            } else {
                out.append(print(annotation));
            }
            out.append(separator);
        }
        return out.toString();
    }

    /** True if {@code annotation} is retained in class files and may be written on a {@code target}. */
    private static boolean copied(AnnotationMirror annotation, ElementType target) {
        if (annotation.getAnnotationType().getKind() != TypeKind.DECLARED) {
            return false; // an unresolved annotation type: javac reports it on the original element
        }
        Element type = annotation.getAnnotationType().asElement();
        Retention retention = type.getAnnotation(Retention.class);
        if (retention != null && retention.value() == RetentionPolicy.SOURCE) {
            return false;
        }
        Target applicable = type.getAnnotation(Target.class);
        return applicable == null || Arrays.asList(applicable.value()).contains(target);
    }

    /** {@code @ConstructorProperties} must list one name per parameter, so the generated one lists the registry first. */
    private static String constructorProperties(AnnotationMirror annotation, String leadingName) {
        List<String> names = new ArrayList<>();
        names.add(string(leadingName));
        for (AnnotationValue value : annotation.getElementValues().values()) {
            if (value.getValue() instanceof List<?> list) {
                for (Object item : list) {
                    names.add(((AnnotationValue) item).accept(VALUE, null));
                }
            }
        }
        return "@" + CONSTRUCTOR_PROPERTIES + "({" + String.join(", ", names) + "})";
    }

    static String print(AnnotationMirror annotation) {
        String name = ((TypeElement) annotation.getAnnotationType().asElement()).getQualifiedName().toString();
        Map<? extends ExecutableElement, ? extends AnnotationValue> values = annotation.getElementValues();
        if (values.isEmpty()) {
            return "@" + name;
        }
        if (values.size() == 1) {
            Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> only = values.entrySet().iterator().next();
            if (only.getKey().getSimpleName().contentEquals("value")) {
                return "@" + name + "(" + only.getValue().accept(VALUE, null) + ")";
            }
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : values.entrySet()) {
            pairs.add(entry.getKey().getSimpleName() + " = " + entry.getValue().accept(VALUE, null));
        }
        return "@" + name + "(" + String.join(", ", pairs) + ")";
    }

    private static final SimpleAnnotationValueVisitor14<String, Void> VALUE = new SimpleAnnotationValueVisitor14<>() {
        @Override
        public String visitBoolean(boolean b, Void unused) {
            return Boolean.toString(b);
        }

        @Override
        public String visitByte(byte b, Void unused) {
            return "(byte) " + b;
        }

        @Override
        public String visitChar(char c, Void unused) {
            return c == '\'' ? "'\\''" : "'" + escape(c) + "'";
        }

        @Override
        public String visitDouble(double d, Void unused) {
            if (Double.isNaN(d)) {
                return "0.0 / 0.0";
            }
            if (Double.isInfinite(d)) {
                return d > 0 ? "1.0 / 0.0" : "-1.0 / 0.0";
            }
            return Double.toString(d);
        }

        @Override
        public String visitFloat(float f, Void unused) {
            if (Float.isNaN(f)) {
                return "0.0f / 0.0f";
            }
            if (Float.isInfinite(f)) {
                return f > 0 ? "1.0f / 0.0f" : "-1.0f / 0.0f";
            }
            return Float.toString(f) + "f";
        }

        @Override
        public String visitInt(int i, Void unused) {
            return Integer.toString(i);
        }

        @Override
        public String visitLong(long i, Void unused) {
            return Long.toString(i) + "L";
        }

        @Override
        public String visitShort(short s, Void unused) {
            return "(short) " + s;
        }

        @Override
        public String visitString(String s, Void unused) {
            return string(s);
        }

        @Override
        public String visitType(TypeMirror t, Void unused) {
            return TypePrinter.print(t) + ".class";
        }

        @Override
        public String visitEnumConstant(VariableElement c, Void unused) {
            return ((TypeElement) c.getEnclosingElement()).getQualifiedName() + "." + c.getSimpleName();
        }

        @Override
        public String visitAnnotation(AnnotationMirror a, Void unused) {
            return print(a);
        }

        @Override
        public String visitArray(List<? extends AnnotationValue> values, Void unused) {
            List<String> items = new ArrayList<>();
            for (AnnotationValue value : values) {
                items.add(value.accept(this, null));
            }
            return "{" + String.join(", ", items) + "}";
        }
    };

    /** A string literal; characters outside printable ASCII are escaped, so the source encoding does not matter. */
    private static String string(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            out.append(c == '"' ? "\\\"" : escape(c));
        }
        return out.append('"').toString();
    }

    private static String escape(char c) {
        return switch (c) {
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            default -> c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : String.valueOf(c);
        };
    }
}
