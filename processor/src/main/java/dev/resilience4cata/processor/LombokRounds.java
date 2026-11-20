package dev.resilience4cata.processor;

import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Whether a root type must wait one round for Lombok. Lombok changes the classes it finds in its first round
 * (constructors, getters, {@code final} for {@code @Value}...) and then forces more rounds. In that first round this
 * processor may see the classes as they were before: when it runs before Lombok (the processor path lists it
 * first), a class with {@code @RequiredArgsConstructor} still shows only its default constructor, so the generated
 * subclass would call a {@code super()} that no longer exists; and even after Lombok, javac shows the class's old
 * modifiers until the next round. Such classes are processed in the next round instead, which Lombok always forces.
 */
final class LombokRounds {

    private LombokRounds() {
    }

    /**
     * True if {@code root} must wait: in the first round, when {@code root}, a type nested in it or a superclass
     * compiled from source carries a {@code lombok.*} annotation on itself or on a member.
     */
    static boolean mustWait(TypeElement root, boolean firstRound) {
        return firstRound && usesLombok(root, new HashSet<>());
    }

    private static boolean usesLombok(TypeElement type, Set<Element> seen) {
        for (TypeElement t = type; t != null && seen.add(t); t = superclass(t)) {
            if (annotated(t, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean annotated(Element element, Set<Element> seen) {
        if (hasLombokAnnotation(element)) {
            return true;
        }
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed instanceof TypeElement nested ? usesLombok(nested, seen) : hasLombokAnnotation(enclosed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLombokAnnotation(Element element) {
        for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
            Element type = annotation.getAnnotationType().asElement();
            if (type instanceof TypeElement t && t.getQualifiedName().toString().startsWith("lombok.")) {
                return true;
            }
        }
        return false;
    }

    private static TypeElement superclass(TypeElement type) {
        TypeMirror superclass = type.getSuperclass();
        return superclass.getKind() == TypeKind.DECLARED ? (TypeElement) ((DeclaredType) superclass).asElement() : null;
    }
}
