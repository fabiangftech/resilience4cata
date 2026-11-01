package dev.resilience4cata.processor;

import dev.resilience4cata.annotation.ResiliencePolicy;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.processing.Messager;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;

/**
 * Collects the attributes of one policy annotation (for example {@code @Retry}) that are explicitly
 * set on an element, directly or through composed annotations marked with {@code @ResiliencePolicy}.
 * Attributes left at their "unset" value are ignored, so they never override anything.
 */
final class PolicyAttributes {

    static final String POLICY = ResiliencePolicy.class.getCanonicalName();

    private final Messager messager;
    private final String annotation;
    private final String simpleName;
    // by element, not by toString(): methods of different types print alike, and each needs its own error
    private final Set<Map.Entry<Element, String>> reported = new HashSet<>();

    // checks the values of one site (an element's annotation and bundles) on their own, before they are merged
    private final Function<Map<String, AnnotationValue>, String> siteCheck;

    PolicyAttributes(Messager messager, Class<?> annotation) {
        this(messager, annotation, values -> null);
    }

    /**
     * @param siteCheck the error message (with its code) for the values that one element sets, merged from its
     *        direct annotation and its bundles, or null if they are valid; reported on that element
     */
    PolicyAttributes(Messager messager, Class<?> annotation,
            Function<Map<String, AnnotationValue>, String> siteCheck) {
        this.messager = messager;
        this.annotation = annotation.getCanonicalName();
        this.simpleName = annotation.getSimpleName();
        this.siteCheck = siteCheck;
    }

    /** The annotation's simple name, for messages: {@code "Retry"}. */
    String simpleName() {
        return simpleName;
    }

    /** The annotation's qualified name. */
    String annotation() {
        return annotation;
    }

    /**
     * Returns the explicitly set attributes, or empty if the element does not use the annotation at
     * all. A direct annotation wins over composed annotations. Two composed annotations that set the
     * same attribute to different values are reported as an error.
     */
    Optional<Map<String, AnnotationValue>> on(Element element) {
        Optional<Map<String, AnnotationValue>> values = collect(element, element, new ArrayDeque<>());
        if (values.isPresent()) {
            String message = siteCheck.apply(values.get());
            if (message != null && reported.add(Map.entry(element, message))) {
                messager.printMessage(Diagnostic.Kind.ERROR, message, element);
            }
        }
        return values;
    }

    private Optional<Map<String, AnnotationValue>> collect(Element element, Element reportOn, Deque<String> path) {
        Map<String, AnnotationValue> composed = new LinkedHashMap<>();
        Map<String, String> composedSource = new LinkedHashMap<>();
        Map<String, AnnotationValue> direct = null;
        boolean present = false;
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            TypeElement type = (TypeElement) mirror.getAnnotationType().asElement();
            String name = type.getQualifiedName().toString();
            if (name.equals(annotation)) {
                direct = explicitValues(mirror);
                present = true;
            } else if (hasAnnotation(type, POLICY) && !path.contains(name)) {
                path.push(name);
                Optional<Map<String, AnnotationValue>> bundle = collect(type, reportOn, path);
                path.pop();
                if (bundle.isPresent()) {
                    present = true;
                    merge(bundle.get(), type, composed, composedSource, reportOn);
                }
            }
        }
        if (!present) {
            return Optional.empty();
        }
        Map<String, AnnotationValue> result = new LinkedHashMap<>(composed);
        if (direct != null) {
            result.putAll(direct);
        }
        return Optional.of(result);
    }

    /** Adds a bundle's values to {@code composed}, reporting values that another bundle set differently. */
    private void merge(Map<String, AnnotationValue> bundle, TypeElement type, Map<String, AnnotationValue> composed,
            Map<String, String> composedSource, Element reportOn) {
        for (Map.Entry<String, AnnotationValue> entry : bundle.entrySet()) {
            AnnotationValue previous = composed.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.toString().equals(entry.getValue().toString())) {
                String message = ProcessorError.BUNDLE_CONFLICT.message("@" + simpleName + "." + entry.getKey()
                        + " is set to " + previous + " by @" + composedSource.get(entry.getKey())
                        + " and to " + entry.getValue() + " by @" + type.getSimpleName()
                        + ". Set it directly on the element to resolve the conflict.");
                if (reported.add(Map.entry(reportOn, message))) {
                    messager.printMessage(Diagnostic.Kind.ERROR, message, reportOn);
                }
            }
            composedSource.putIfAbsent(entry.getKey(), type.getSimpleName().toString());
        }
    }

    static boolean hasAnnotation(Element element, String annotation) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().contentEquals(annotation)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, AnnotationValue> explicitValues(AnnotationMirror mirror) {
        Map<String, AnnotationValue> values = new LinkedHashMap<>();
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : mirror.getElementValues().entrySet()) {
            if (!isUnset(entry.getValue())) {
                values.put(entry.getKey().getSimpleName().toString(), entry.getValue());
            }
        }
        return values;
    }

    /** True for the sentinel values that mean "not set": -1, "", {}, void.class and INHERIT. */
    private static boolean isUnset(AnnotationValue value) {
        Object v = value.getValue();
        if (v instanceof Integer i) {
            return i == -1;
        }
        if (v instanceof Double d) {
            return d == -1;
        }
        if (v instanceof String s) {
            return s.isEmpty();
        }
        if (v instanceof List<?> list) {
            return list.isEmpty();
        }
        if (v instanceof VariableElement constant) {
            return constant.getSimpleName().contentEquals("INHERIT");
        }
        if (v instanceof TypeMirror type) {
            return type.getKind() == TypeKind.VOID;
        }
        return false;
    }
}
