package dev.resilience4cata.processor;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

/**
 * Prints types as valid Java source, fully qualified and without type annotations. Needed because
 * {@code TypeMirror.toString()} can include annotations such as {@code @Nullable} in positions where
 * they are not valid source.
 */
final class TypePrinter {

    private TypePrinter() {
    }

    static String print(TypeMirror type) {
        return switch (type.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE, VOID ->
                    type.getKind().name().toLowerCase(Locale.ROOT);
            case ARRAY -> print(((ArrayType) type).getComponentType()) + "[]";
            case TYPEVAR -> ((TypeVariable) type).asElement().getSimpleName().toString();
            case WILDCARD -> wildcard((WildcardType) type);
            case INTERSECTION -> join(((IntersectionType) type).getBounds(), " & ");
            case DECLARED -> declared((DeclaredType) type);
            default -> type.toString();
        };
    }

    static String join(List<? extends TypeMirror> types, String separator) {
        return types.stream().map(TypePrinter::print).collect(Collectors.joining(separator));
    }

    private static String declared(DeclaredType type) {
        String name = ((TypeElement) type.asElement()).getQualifiedName().toString();
        List<? extends TypeMirror> arguments = type.getTypeArguments();
        return arguments.isEmpty() ? name : name + "<" + join(arguments, ", ") + ">";
    }

    private static String wildcard(WildcardType type) {
        if (type.getExtendsBound() != null) {
            return "? extends " + print(type.getExtendsBound());
        }
        if (type.getSuperBound() != null) {
            return "? super " + print(type.getSuperBound());
        }
        return "?";
    }
}
