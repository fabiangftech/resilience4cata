package dev.resilience4cata.processor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * The class-specific half of the subclass style: for an annotated non-final class {@code C}, the processor
 * generates {@code C$Resilient extends C}, which overrides every protected method and ends each override
 * with {@code super.method(args)}. Because the user's code calls its own methods virtually, calls such as
 * {@code this.other()} go through the override too (self-invocation is intercepted).
 *
 * <p>This class checks that {@code C} can be extended, lists the methods the subclass may override, merges
 * the attributes of each policy along the class hierarchy, and describes the constructors to mirror. The
 * layers themselves are written by {@link ResilienceProcessor}, exactly as for interfaces.
 *
 * <p>Everything here reads only {@code C}, its supertypes and their members, which keeps the processor
 * isolating: {@code C} is the only originating element of {@code C$Resilient}.
 */
final class SubclassWriter {

    private final Elements elements;
    private final Types types;
    private final Messager messager;

    SubclassWriter(Elements elements, Types types, Messager messager) {
        this.elements = elements;
        this.types = types;
        this.messager = messager;
    }

    /** A constructor of the generated class: a leading {@code ResilienceRegistry registry}, then {@code parameters}. */
    record Constructor(String typeVariables, String parameters, String arguments, String throwsClause) {
    }

    /**
     * Reports every reason why {@code type} cannot be extended by the generated subclass, and every method
     * annotated at method level (on the class, a superclass or an interface it implements) that the
     * subclass cannot override. {@code annotated} tells whether a method carries a policy annotation itself.
     * Returns true if there was no error.
     */
    boolean check(TypeElement type, Predicate<Element> annotated) {
        String name = type.getSimpleName().toString();
        Set<Modifier> modifiers = type.getModifiers();
        String alternative = " Move the annotations to an interface and wrap the implementation with Resilient.of(...),"
                + " or annotate a class that can be extended.";
        if (modifiers.contains(Modifier.FINAL)) {
            return invalid(type, name + " is final, so the generated subclass cannot extend it. Remove final." + alternative);
        }
        if (modifiers.contains(Modifier.SEALED)) {
            return invalid(type, name + " is sealed, so the generated subclass cannot extend it. Make it non-sealed or"
                    + " a regular class." + alternative);
        }
        if (modifiers.contains(Modifier.ABSTRACT)) {
            return invalid(type, name + " is abstract: the generated subclass must be instantiable and calls super for"
                    + " each method. Annotate a concrete subclass of " + name + " instead, or an interface.");
        }
        for (Element e = type; e instanceof TypeElement t; e = t.getEnclosingElement()) {
            if (t.getModifiers().contains(Modifier.PRIVATE)) {
                return invalid(type, "Resilience annotations need a non-private class: " + t.getSimpleName()
                        + " is private, so the generated subclass cannot extend " + name + ".");
            }
        }
        if (type.getNestingKind() == NestingKind.MEMBER && !modifiers.contains(Modifier.STATIC)
                && !type.getEnclosingElement().getKind().isInterface()) {
            return invalid(type, name + " is an inner class: the generated subclass would need an instance of "
                    + type.getEnclosingElement().getSimpleName() + ". Make it a static nested class.");
        }
        boolean constructor = false;
        for (ExecutableElement c : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            constructor |= !c.getModifiers().contains(Modifier.PRIVATE);
        }
        if (!constructor) {
            return invalid(type, name + " has only private constructors, so the generated subclass cannot call any of"
                    + " them. Make at least one constructor package-private, protected or public.");
        }
        boolean valid = true;
        for (ExecutableElement method : candidates(type).values()) {
            Set<Modifier> methodModifiers = method.getModifiers();
            boolean isFinal = methodModifiers.contains(Modifier.FINAL);
            boolean hidden = !overridable(type, method);
            if ((!isFinal && !hidden) || !annotatedAnywhere(type, method, annotated)) {
                continue;
            }
            String signature = method.getSimpleName() + "(" + TypePrinter.join(member(type, method).getParameterTypes(), ", ") + ")";
            Element reportOn = method.getEnclosingElement().equals(type) ? method : type;
            String where = reportOn == method ? "" : " (inherited from "
                    + ((TypeElement) method.getEnclosingElement()).getQualifiedName() + ")";
            if (isFinal) {
                valid = invalid(reportOn, signature + where + " is final, so the generated subclass cannot override it"
                        + " and its resilience annotations would be ignored. Remove final, or remove the annotations.");
            } else {
                valid = invalid(reportOn, signature + where + " is package-private in another package, so the generated"
                        + " subclass cannot override it and its resilience annotations would be ignored. Make it"
                        + " protected or public.");
            }
        }
        for (TypeElement c = superclass(type); c != null; c = superclass(c)) {
            if (elements.getPackageOf(c).equals(elements.getPackageOf(type))) {
                continue;
            }
            for (ExecutableElement method : ElementFilter.methodsIn(c.getEnclosedElements())) {
                Set<Modifier> methodModifiers = method.getModifiers();
                if (!methodModifiers.contains(Modifier.PUBLIC) && !methodModifiers.contains(Modifier.PROTECTED)
                        && !methodModifiers.contains(Modifier.PRIVATE) && !methodModifiers.contains(Modifier.STATIC)
                        && annotated.test(method)) {
                    valid = invalid(type, method.getSimpleName() + "(" + TypePrinter.join(member(type, method)
                            .getParameterTypes(), ", ") + ") (inherited from " + c.getQualifiedName() + ") is"
                            + " package-private in another package, so the generated subclass cannot override it and"
                            + " its resilience annotations would be ignored. Make it protected or public.");
                }
            }
        }
        return valid;
    }

    /**
     * The methods the generated subclass may override, as members of {@code type}: inherited and declared
     * instance methods, including default methods, except private, static and final ones, methods
     * declared in {@code Object}, and package-private methods of a superclass in another package.
     */
    List<ExecutableElement> members(TypeElement type) {
        List<ExecutableElement> methods = new ArrayList<>();
        for (ExecutableElement method : candidates(type).values()) {
            if (!method.getModifiers().contains(Modifier.FINAL) && overridable(type, method)) {
                methods.add(method);
            }
        }
        return methods;
    }

    /**
     * Merges one policy's attributes for {@code method} as a member of {@code type}, lowest precedence first:
     * the interface that declares each interface method it implements, that interface method, the classes
     * that declare it (topmost superclass first, then {@code type}), and the class methods (topmost first,
     * the implementation last). Methods that override {@code Object}'s ({@code toString}, {@code equals}...)
     * only take annotations written on the methods themselves, never type-level ones.
     */
    Optional<Map<String, AnnotationValue>> effective(PolicyAttributes policy, TypeElement type, ExecutableElement method) {
        boolean objectMethod = overridesObjectMethod(type, method);
        List<Optional<Map<String, AnnotationValue>>> levels = new ArrayList<>();
        for (ExecutableElement implemented : interfaceMethods(type, method)) {
            if (!objectMethod) {
                levels.add(policy.on(implemented.getEnclosingElement()));
            }
            levels.add(policy.on(implemented));
        }
        List<ExecutableElement> chain = classMethods(type, method);
        if (!objectMethod) {
            Set<Element> declaring = new LinkedHashSet<>();
            for (ExecutableElement declared : chain) {
                declaring.add(declared.getEnclosingElement());
            }
            declaring.remove(type);
            for (Element d : declaring) {
                levels.add(policy.on(d));
            }
            levels.add(policy.on(type));
        }
        for (ExecutableElement declared : chain) {
            levels.add(policy.on(declared));
        }
        Map<String, AnnotationValue> merged = null;
        for (Optional<Map<String, AnnotationValue>> level : levels) {
            if (level.isPresent()) {
                if (merged == null) {
                    merged = new LinkedHashMap<>();
                }
                merged.putAll(level.get());
            }
        }
        return Optional.ofNullable(merged);
    }

    /** The constructors to mirror: every non-private constructor of {@code type}, in declaration order. */
    List<Constructor> constructors(TypeElement type) {
        List<Constructor> constructors = new ArrayList<>();
        for (ExecutableElement c : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            if (c.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            ExecutableType constructorType = member(type, c);
            List<? extends TypeMirror> parameterTypes = constructorType.getParameterTypes();
            StringBuilder parameters = new StringBuilder();
            List<String> arguments = new ArrayList<>();
            for (int i = 0; i < parameterTypes.size(); i++) {
                String parameterType = TypePrinter.print(parameterTypes.get(i));
                if (c.isVarArgs() && i == parameterTypes.size() - 1) {
                    parameterType = parameterType.substring(0, parameterType.length() - 2) + "...";
                }
                parameters.append(", ").append(parameterType).append(" p").append(i);
                arguments.add("p" + i);
            }
            List<? extends TypeMirror> thrown = constructorType.getThrownTypes();
            constructors.add(new Constructor(typeVariables(constructorType.getTypeVariables()), parameters.toString(),
                    String.join(", ", arguments), thrown.isEmpty() ? "" : " throws " + TypePrinter.join(thrown, ", ")));
        }
        return constructors;
    }

    /** Instance methods of {@code type} that a subclass could see, one per signature, most specific first. */
    private Map<String, ExecutableElement> candidates(TypeElement type) {
        Map<String, ExecutableElement> methods = new LinkedHashMap<>();
        for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(type))) {
            Set<Modifier> modifiers = method.getModifiers();
            TypeElement declaring = (TypeElement) method.getEnclosingElement();
            if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.PRIVATE)
                    || declaring.getQualifiedName().contentEquals("java.lang.Object")) {
                continue;
            }
            String key = method.getSimpleName() + "(" + member(type, method).getParameterTypes().stream()
                    .map(t -> TypePrinter.print(types.erasure(t))).collect(Collectors.joining(",")) + ")";
            ExecutableElement previous = methods.get(key);
            // A concrete class implements every abstract method; prefer the implementation over the declaration.
            if (previous == null || (previous.getModifiers().contains(Modifier.ABSTRACT)
                    && !modifiers.contains(Modifier.ABSTRACT))) {
                methods.put(key, method);
            }
        }
        methods.values().removeIf(m -> m.getModifiers().contains(Modifier.ABSTRACT));
        return methods;
    }

    /** False for a package-private method declared in another package, which a subclass cannot override. */
    private boolean overridable(TypeElement type, ExecutableElement method) {
        Set<Modifier> modifiers = method.getModifiers();
        if (modifiers.contains(Modifier.PUBLIC) || modifiers.contains(Modifier.PROTECTED)
                || method.getEnclosingElement().getKind().isInterface()) {
            return true;
        }
        return elements.getPackageOf(method).equals(elements.getPackageOf(type));
    }

    private boolean annotatedAnywhere(TypeElement type, ExecutableElement method, Predicate<Element> annotated) {
        for (ExecutableElement m : interfaceMethods(type, method)) {
            if (annotated.test(m)) {
                return true;
            }
        }
        for (ExecutableElement m : classMethods(type, method)) {
            if (annotated.test(m)) {
                return true;
            }
        }
        return false;
    }

    /** Interface methods that {@code method} implements or is, in breadth-first order of the interfaces. */
    private List<ExecutableElement> interfaceMethods(TypeElement type, ExecutableElement method) {
        List<ExecutableElement> found = new ArrayList<>();
        for (TypeElement iface : interfaces(type)) {
            for (ExecutableElement candidate : ElementFilter.methodsIn(iface.getEnclosedElements())) {
                if (sameSignature(type, method, candidate)) {
                    found.add(candidate);
                }
            }
        }
        return found;
    }

    /** The declarations of {@code method} in the class hierarchy of {@code type}, topmost superclass first. */
    private List<ExecutableElement> classMethods(TypeElement type, ExecutableElement method) {
        List<ExecutableElement> found = new ArrayList<>();
        for (TypeElement c = type; c != null; c = superclass(c)) {
            for (ExecutableElement candidate : ElementFilter.methodsIn(c.getEnclosedElements())) {
                if (sameSignature(type, method, candidate)) {
                    found.add(candidate);
                }
            }
        }
        Collections.reverse(found);
        return found;
    }

    private boolean overridesObjectMethod(TypeElement type, ExecutableElement method) {
        TypeElement object = elements.getTypeElement("java.lang.Object");
        for (ExecutableElement candidate : ElementFilter.methodsIn(object.getEnclosedElements())) {
            Set<Modifier> modifiers = candidate.getModifiers();
            if (!modifiers.contains(Modifier.STATIC) && !modifiers.contains(Modifier.PRIVATE)
                    && candidate.getSimpleName().equals(method.getSimpleName())
                    && types.isSubsignature(member(type, method), (ExecutableType) candidate.asType())) {
                return true;
            }
        }
        return false;
    }

    /** True if {@code candidate} is {@code method}, or {@code method} overrides it, both seen as members of {@code type}. */
    private boolean sameSignature(TypeElement type, ExecutableElement method, ExecutableElement candidate) {
        if (candidate.equals(method)) {
            return true;
        }
        Set<Modifier> modifiers = candidate.getModifiers();
        return !modifiers.contains(Modifier.STATIC) && !modifiers.contains(Modifier.PRIVATE)
                && candidate.getSimpleName().equals(method.getSimpleName())
                && types.isSubsignature(member(type, method), member(type, candidate));
    }

    /** The superclass of {@code type}, or null for {@code Object} and for a direct subclass of {@code Object}. */
    private static TypeElement superclass(TypeElement type) {
        TypeMirror superclass = type.getSuperclass();
        if (superclass.getKind() != TypeKind.DECLARED) {
            return null;
        }
        TypeElement element = (TypeElement) ((DeclaredType) superclass).asElement();
        return element.getQualifiedName().contentEquals("java.lang.Object") ? null : element;
    }

    /** Every interface {@code type} implements, directly or through superclasses and superinterfaces. */
    private Set<TypeElement> interfaces(TypeElement type) {
        Set<TypeElement> found = new LinkedHashSet<>();
        Deque<TypeMirror> pending = new ArrayDeque<>(types.directSupertypes(type.asType()));
        while (!pending.isEmpty()) {
            TypeMirror next = pending.removeFirst();
            if (next.getKind() != TypeKind.DECLARED) {
                continue;
            }
            TypeElement element = (TypeElement) ((DeclaredType) next).asElement();
            if (element.getKind().isInterface() ? found.add(element) : true) {
                pending.addAll(types.directSupertypes(next));
            }
        }
        return found;
    }

    private ExecutableType member(TypeElement type, ExecutableElement method) {
        return (ExecutableType) types.asMemberOf((DeclaredType) type.asType(), method);
    }

    private static String typeVariables(List<? extends TypeVariable> variables) {
        if (variables.isEmpty()) {
            return "";
        }
        List<String> declarations = new ArrayList<>();
        for (TypeVariable variable : variables) {
            TypeMirror bound = variable.getUpperBound();
            List<? extends TypeMirror> bounds = bound.getKind() == TypeKind.INTERSECTION
                    ? ((IntersectionType) bound).getBounds() : List.of(bound);
            boolean plain = bounds.size() == 1 && TypePrinter.print(bounds.get(0)).equals("java.lang.Object");
            declarations.add(variable.asElement().getSimpleName() + (plain ? "" : " extends " + TypePrinter.join(bounds, " & ")));
        }
        return "<" + String.join(", ", declarations) + "> ";
    }

    private boolean invalid(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
        return false;
    }
}
