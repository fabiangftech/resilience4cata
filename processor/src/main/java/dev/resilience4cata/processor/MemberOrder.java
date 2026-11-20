package dev.resilience4cata.processor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The methods of {@link Elements#getAllMembers}, in one order whatever the compiler: {@code getAllMembers} leaves
 * the order to the implementation, and the Eclipse compiler's differs from javac's, which would change the
 * generated code (its field numbers) between a build in Eclipse and one with Gradle or Maven.
 *
 * <p>The order is javac's, so that its output does not change: the declaring types from the lowest rank (the
 * longest path to a type without supertypes) to the highest, types of one rank by qualified name, last first;
 * the methods of one type in declaration order ({@link TypeElement#getEnclosedElements}).
 */
final class MemberOrder {

    private final Elements elements;
    private final Types types;

    MemberOrder(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    /** The methods among {@code type}'s members, in the order described above. */
    List<ExecutableElement> methods(TypeElement type) {
        List<ExecutableElement> methods = new ArrayList<>(ElementFilter.methodsIn(elements.getAllMembers(type)));
        Map<Element, Integer> ranks = new HashMap<>();
        rank(type, ranks);
        Map<Element, Integer> positions = new HashMap<>();
        for (ExecutableElement method : methods) {
            Element declaring = method.getEnclosingElement();
            if (!positions.containsKey(method)) {
                int i = 0;
                for (Element member : declaring.getEnclosedElements()) {
                    positions.put(member, i++);
                }
            }
        }
        methods.sort(Comparator
                .comparingInt((ExecutableElement m) -> ranks.getOrDefault(m.getEnclosingElement(), 0))
                .thenComparing((ExecutableElement m) -> qualifiedName(m.getEnclosingElement()), Comparator.reverseOrder())
                .thenComparingInt(m -> positions.getOrDefault(m, 0)));
        return methods;
    }

    private int rank(TypeElement type, Map<Element, Integer> ranks) {
        Integer known = ranks.get(type);
        if (known != null) {
            return known;
        }
        int rank = 0;
        for (TypeMirror supertype : types.directSupertypes(type.asType())) {
            if (supertype.getKind() == TypeKind.DECLARED) {
                rank = Math.max(rank, rank((TypeElement) ((DeclaredType) supertype).asElement(), ranks) + 1);
            }
        }
        ranks.put(type, rank);
        return rank;
    }

    private static String qualifiedName(Element type) {
        return type instanceof TypeElement t ? t.getQualifiedName().toString() : "";
    }
}
