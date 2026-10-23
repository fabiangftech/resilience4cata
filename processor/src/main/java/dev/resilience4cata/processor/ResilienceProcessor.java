package dev.resilience4cata.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Generates, for every interface that uses resilience annotations, a class named
 * {@code <Interface>$Resilient} that implements the interface, delegates every method, and applies
 * the declared policies. {@code Resilient.of(...)} in the core module finds it by that name.
 */
public final class ResilienceProcessor extends AbstractProcessor {

    private static final String SUFFIX = "$Resilient";
    private static final String POLICY_TYPE = "dev.resilience4cata.generated.RetryPolicy";
    private static final String REGISTRY_TYPE = "dev.resilience4cata.ResilienceRegistry";
    private static final String CONFIG_TYPE = "dev.resilience4cata.RetryConfig";

    private Elements elements;
    private Types types;
    private Messager messager;
    private RetryAttributes retryAttributes;
    private final Set<String> processed = new HashSet<>();

    /** Creates the processor. Called by the compiler through {@link java.util.ServiceLoader}. */
    public ResilienceProcessor() {
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elements = processingEnv.getElementUtils();
        types = processingEnv.getTypeUtils();
        messager = processingEnv.getMessager();
        retryAttributes = new RetryAttributes(messager);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        // Composed annotations are user-defined, so their names cannot be listed up front.
        return Set.of("*");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element root : roundEnv.getRootElements()) {
            scan(root);
        }
        return false;
    }

    private void scan(Element element) {
        if (element instanceof TypeElement type) {
            inspect(type);
            for (Element enclosed : type.getEnclosedElements()) {
                scan(enclosed);
            }
        }
    }

    private void inspect(TypeElement type) {
        switch (type.getKind()) {
            case ANNOTATION_TYPE -> {
                if (RetryAttributes.hasAnnotation(type, RetryAttributes.RETRY)
                        && !RetryAttributes.hasAnnotation(type, RetryAttributes.POLICY)) {
                    error(type, "@" + type.getSimpleName() + " uses @Retry but is not marked with @ResiliencePolicy,"
                            + " so its policies would be ignored. Add @ResiliencePolicy to it.");
                }
            }
            case INTERFACE -> generateIfNeeded(type);
            default -> {
                if (usesRetry(type)) {
                    error(type, "@Retry is only supported on interfaces for now. Declare the methods in an"
                            + " interface and wrap the implementation with Resilient.of(...).");
                }
            }
        }
    }

    private boolean usesRetry(TypeElement type) {
        if (retryAttributes.on(type).isPresent()) {
            return true;
        }
        for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
            if (retryAttributes.on(method).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private void generateIfNeeded(TypeElement iface) {
        String binaryName = elements.getBinaryName(iface).toString();
        if (!processed.add(binaryName)) {
            return;
        }
        boolean valid = checkDeclaredMethods(iface);

        Optional<Map<String, AnnotationValue>> typeLevel = retryAttributes.on(iface);
        List<MethodModel> methods = new ArrayList<>();
        boolean anyPolicy = false;
        for (ExecutableElement method : members(iface)) {
            ExecutableType type = (ExecutableType) types.asMemberOf((DeclaredType) iface.asType(), method);
            Optional<Map<String, AnnotationValue>> retry = effectiveRetry(iface, typeLevel, method);
            RetryModel retryModel = null;
            if (retry.isPresent()) {
                anyPolicy = true;
                retryModel = retryModel(iface, method, type, retry.get());
                valid &= retryModel != null;
            }
            methods.add(new MethodModel(method, type, retryModel));
        }
        if (!anyPolicy || !valid) {
            return;
        }
        if (iface.getModifiers().contains(Modifier.PRIVATE)) {
            error(iface, "Resilience annotations need a non-private interface: the generated class must implement it.");
            return;
        }
        write(iface, binaryName, methods);
    }

    /** Static and private interface methods cannot be wrapped. */
    private boolean checkDeclaredMethods(TypeElement iface) {
        boolean valid = true;
        for (ExecutableElement method : ElementFilter.methodsIn(iface.getEnclosedElements())) {
            Set<Modifier> modifiers = method.getModifiers();
            if ((modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.PRIVATE))
                    && retryAttributes.on(method).isPresent()) {
                error(method, "@Retry cannot be used on static or private methods: calls to them cannot be intercepted.");
                valid = false;
            }
        }
        return valid;
    }

    /** Every method the wrapper must implement, including inherited and default methods. */
    private List<ExecutableElement> members(TypeElement iface) {
        Map<String, ExecutableElement> methods = new LinkedHashMap<>();
        for (ExecutableElement method : ElementFilter.methodsIn(elements.getAllMembers(iface))) {
            Set<Modifier> modifiers = method.getModifiers();
            if (method.getEnclosingElement().getKind() != ElementKind.INTERFACE
                    || modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.PRIVATE)) {
                continue;
            }
            ExecutableType type = (ExecutableType) types.asMemberOf((DeclaredType) iface.asType(), method);
            methods.putIfAbsent(method.getSimpleName() + "(" + erasedParameters(type) + ")", method);
        }
        return new ArrayList<>(methods.values());
    }

    /** Merges, lowest precedence first: declaring interface, wrapped interface, method. */
    private Optional<Map<String, AnnotationValue>> effectiveRetry(TypeElement iface,
            Optional<Map<String, AnnotationValue>> typeLevel, ExecutableElement method) {
        TypeElement declaring = (TypeElement) method.getEnclosingElement();
        Optional<Map<String, AnnotationValue>> declaringLevel =
                declaring.equals(iface) ? Optional.empty() : retryAttributes.on(declaring);
        Optional<Map<String, AnnotationValue>> methodLevel = retryAttributes.on(method);
        if (declaringLevel.isEmpty() && typeLevel.isEmpty() && methodLevel.isEmpty()) {
            return Optional.empty();
        }
        Map<String, AnnotationValue> merged = new LinkedHashMap<>();
        declaringLevel.ifPresent(merged::putAll);
        typeLevel.ifPresent(merged::putAll);
        methodLevel.ifPresent(merged::putAll);
        return Optional.of(merged);
    }

    /** Validates the attributes and turns them into builder calls. Returns null after reporting errors. */
    private RetryModel retryModel(TypeElement iface, ExecutableElement method, ExecutableType type,
            Map<String, AnnotationValue> attributes) {
        TypeMirror returnType = types.erasure(type.getReturnType());
        TypeElement completionStage = elements.getTypeElement("java.util.concurrent.CompletionStage");
        if (completionStage != null && types.isAssignable(returnType, types.erasure(completionStage.asType()))) {
            error(method, "@Retry on methods that return a CompletionStage is not supported yet.");
            return null;
        }
        boolean valid = true;
        String name = "";
        String config = "";
        List<String> overrides = new ArrayList<>();
        for (Map.Entry<String, AnnotationValue> entry : attributes.entrySet()) {
            String attribute = entry.getKey();
            Object value = entry.getValue().getValue();
            switch (attribute) {
                case "name" -> name = (String) value;
                case "config" -> config = (String) value;
                case "maxAttempts" -> {
                    int attempts = (Integer) value;
                    if (attempts < 1) {
                        valid = invalid(method, "@Retry.maxAttempts must be at least 1, was " + attempts);
                    } else {
                        overrides.add("maxAttempts(" + attempts + ")");
                    }
                }
                case "delay", "maxDelay", "maxDuration" -> {
                    try {
                        long nanos = Durations.parseNanos((String) value);
                        overrides.add(attribute + "(java.time.Duration.ofNanos(" + nanos + "L))");
                    } catch (IllegalArgumentException e) {
                        valid = invalid(method, "@Retry." + attribute + " = \"" + value + "\" is not a valid duration: "
                                + e.getMessage() + ". Use " + Durations.GRAMMAR + ".");
                    }
                }
                case "multiplier" -> {
                    double multiplier = (Double) value;
                    if (!(multiplier >= 1) || Double.isInfinite(multiplier)) {
                        valid = invalid(method, "@Retry.multiplier must be a finite number >= 1, was " + multiplier);
                    } else {
                        overrides.add("multiplier(" + multiplier + ")");
                    }
                }
                case "backoff" -> overrides.add("backoff(" + CONFIG_TYPE + ".Backoff." + constant(value) + ")");
                case "jitter" -> overrides.add("jitter(" + CONFIG_TYPE + ".Jitter." + constant(value) + ")");
                case "retryOn", "abortOn" -> overrides.add(attribute + "(java.util.List.of(" + classList(value) + "))");
                case "classifier" -> valid = invalid(method, "@Retry.classifier is not supported yet.");
                case "enabled" -> {
                    if (value.equals("true") || value.equals("false")) {
                        overrides.add("enabled(" + value + ")");
                    } else {
                        valid = invalid(method, "@Retry.enabled must be \"\", \"true\" or \"false\", was \"" + value + "\"");
                    }
                }
                default -> valid = invalid(method, "Unknown @Retry attribute " + attribute
                        + ". The processor and the annotations jar have different versions.");
            }
        }
        if (!valid) {
            return null;
        }
        String instanceId = iface.getQualifiedName() + "#" + method.getSimpleName() + "(" + erasedParameters(type) + ")";
        return new RetryModel(instanceId, name, config, overrides);
    }

    private void write(TypeElement iface, String binaryName, List<MethodModel> methods) {
        String packageName = elements.getPackageOf(iface).getQualifiedName().toString();
        String simpleName = (packageName.isEmpty() ? binaryName : binaryName.substring(packageName.length() + 1)) + SUFFIX;
        String typeParameters = typeParameters(iface.getTypeParameters());
        String typeArguments = iface.getTypeParameters().isEmpty() ? ""
                : iface.getTypeParameters().stream().map(p -> p.getSimpleName().toString())
                        .collect(Collectors.joining(", ", "<", ">"));
        String ifaceType = iface.getQualifiedName() + typeArguments;

        StringBuilder out = new StringBuilder();
        out.append("// Generated by the resilience4cata annotation processor. Do not edit.\n");
        if (!packageName.isEmpty()) {
            out.append("package ").append(packageName).append(";\n\n");
        }
        out.append("@SuppressWarnings({\"deprecation\", \"removal\"})\n");
        out.append("public final class ").append(simpleName).append(typeParameters)
                .append(" implements ").append(ifaceType).append(" {\n\n");
        out.append("    private final ").append(ifaceType).append(" delegate;\n");
        for (int i = 0; i < methods.size(); i++) {
            if (methods.get(i).retry() != null) {
                out.append("    private final ").append(POLICY_TYPE).append(" retry").append(i).append(";\n");
            }
        }
        out.append("\n    public ").append(simpleName).append("(").append(ifaceType).append(" delegate, ")
                .append(REGISTRY_TYPE).append(" registry) {\n");
        out.append("        this.delegate = java.util.Objects.requireNonNull(delegate, \"delegate\");\n");
        out.append("        java.util.Objects.requireNonNull(registry, \"registry\");\n");
        for (int i = 0; i < methods.size(); i++) {
            RetryModel retry = methods.get(i).retry();
            if (retry != null) {
                out.append("        this.retry").append(i).append(" = registry.retryPolicy(")
                        .append(literal(retry.instanceId())).append(", ").append(literal(retry.name())).append(", ")
                        .append(literal(retry.config())).append(", ").append(overrides(retry.overrides())).append(");\n");
            }
        }
        out.append("    }\n");
        for (int i = 0; i < methods.size(); i++) {
            writeMethod(out, methods.get(i), i);
        }
        out.append("}\n");

        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, iface);
            try (Writer writer = file.openWriter()) {
                writer.write(out.toString());
            }
        } catch (IOException e) {
            error(iface, "Could not write " + qualifiedName + ": " + e.getMessage());
        }
    }

    private void writeMethod(StringBuilder out, MethodModel model, int index) {
        ExecutableElement method = model.method();
        ExecutableType type = model.type();
        List<? extends TypeMirror> parameterTypes = type.getParameterTypes();
        List<String> parameters = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < parameterTypes.size(); i++) {
            String parameterType = TypePrinter.print(parameterTypes.get(i));
            if (method.isVarArgs() && i == parameterTypes.size() - 1) {
                parameterType = parameterType.substring(0, parameterType.length() - 2) + "...";
            }
            parameters.add(parameterType + " p" + i);
            arguments.add("p" + i);
        }
        boolean isVoid = type.getReturnType().getKind() == TypeKind.VOID;
        String call = "this.delegate." + method.getSimpleName() + "(" + String.join(", ", arguments) + ")";

        out.append("\n    @Override\n    public ");
        if (!type.getTypeVariables().isEmpty()) {
            out.append(methodTypeVariables(type.getTypeVariables())).append(' ');
        }
        out.append(TypePrinter.print(type.getReturnType())).append(' ').append(method.getSimpleName())
                .append('(').append(String.join(", ", parameters)).append(')');
        if (!type.getThrownTypes().isEmpty()) {
            out.append(" throws ").append(TypePrinter.join(type.getThrownTypes(), ", "));
        }
        out.append(" {\n");
        if (model.retry() == null) {
            out.append("        ").append(isVoid ? "" : "return ").append(call).append(";\n");
        } else {
            out.append("        final ").append(POLICY_TYPE).append(" r4c$retry = this.retry").append(index).append(";\n");
            out.append("        final long r4c$start = r4c$retry.start();\n");
            out.append("        int r4c$attempts = 0;\n");
            out.append("        while (true) {\n");
            out.append("            try {\n");
            if (isVoid) {
                out.append("                ").append(call).append(";\n");
                out.append("                return;\n");
            } else {
                out.append("                return ").append(call).append(";\n");
            }
            out.append("            } catch (java.lang.Throwable r4c$e) {\n");
            out.append("                final long r4c$delay = r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start);\n");
            out.append("                if (r4c$delay < 0 || !r4c$retry.pause(r4c$delay)) {\n");
            out.append("                    throw r4c$e;\n");
            out.append("                }\n");
            out.append("            }\n");
            out.append("        }\n");
        }
        out.append("    }\n");
    }

    private static String typeParameters(List<? extends TypeParameterElement> parameters) {
        if (parameters.isEmpty()) {
            return "";
        }
        List<String> declarations = new ArrayList<>();
        for (TypeParameterElement parameter : parameters) {
            declarations.add(parameter.getSimpleName() + bounds(parameter.getBounds()));
        }
        return "<" + String.join(", ", declarations) + ">";
    }

    private static String methodTypeVariables(List<? extends TypeVariable> variables) {
        List<String> declarations = new ArrayList<>();
        for (TypeVariable variable : variables) {
            TypeMirror bound = variable.getUpperBound();
            List<? extends TypeMirror> bounds = bound.getKind() == TypeKind.INTERSECTION
                    ? ((javax.lang.model.type.IntersectionType) bound).getBounds() : List.of(bound);
            declarations.add(variable.asElement().getSimpleName() + bounds(bounds));
        }
        return "<" + String.join(", ", declarations) + ">";
    }

    private static String bounds(List<? extends TypeMirror> bounds) {
        if (bounds.isEmpty() || (bounds.size() == 1 && TypePrinter.print(bounds.get(0)).equals("java.lang.Object"))) {
            return "";
        }
        return " extends " + TypePrinter.join(bounds, " & ");
    }

    private String erasedParameters(ExecutableType type) {
        return type.getParameterTypes().stream().map(t -> TypePrinter.print(types.erasure(t)))
                .collect(Collectors.joining(","));
    }

    private String classList(Object value) {
        List<String> classes = new ArrayList<>();
        for (Object element : (List<?>) value) {
            TypeMirror type = (TypeMirror) ((AnnotationValue) element).getValue();
            classes.add(TypePrinter.print(types.erasure(type)) + ".class");
        }
        return String.join(", ", classes);
    }

    private static String constant(Object value) {
        return ((VariableElement) value).getSimpleName().toString();
    }

    private static String overrides(List<String> overrides) {
        if (overrides.isEmpty()) {
            return "null";
        }
        StringBuilder lambda = new StringBuilder("r4c$b -> {\n");
        for (String override : overrides) {
            lambda.append("            r4c$b.").append(override).append(";\n");
        }
        return lambda.append("        }").toString();
    }

    private static String literal(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private boolean invalid(Element element, String message) {
        error(element, message);
        return false;
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private record MethodModel(ExecutableElement method, ExecutableType type, RetryModel retry) {
    }

    private record RetryModel(String instanceId, String name, String config, List<String> overrides) {
    }
}
