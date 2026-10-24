package dev.resilience4cata.processor;

import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.EnumMap;
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
import javax.lang.model.type.IntersectionType;
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
 *
 * <p>Policies are nested in the default order, outermost first: Retry, CircuitBreaker, Timeout. Each
 * attempt is recorded by the breaker, an open breaker stops the retries, and the timeout applies to
 * each attempt. The public method holds the outermost layer; every inner layer is a private method
 * of the generated class, which the JIT inlines like any statically bound call.
 */
public final class ResilienceProcessor extends AbstractProcessor {

    private static final String SUFFIX = "$Resilient";
    private static final String REGISTRY_TYPE = "dev.resilience4cata.ResilienceRegistry";

    /** Supported policies, in nesting order: outermost first. */
    private enum Kind {
        RETRY(Retry.class, "retry", "dev.resilience4cata.generated.RetryPolicy", "retryPolicy",
                "dev.resilience4cata.RetryConfig"),
        CIRCUIT_BREAKER(CircuitBreaker.class, "circuitBreaker", "dev.resilience4cata.generated.CircuitBreakerPolicy",
                "circuitBreakerPolicy", "dev.resilience4cata.CircuitBreakerConfig"),
        TIMEOUT(Timeout.class, "timeout", "dev.resilience4cata.generated.TimeoutPolicy", "timeoutPolicy",
                "dev.resilience4cata.TimeoutConfig");

        final Class<?> annotation;
        final String field;
        final String policyType;
        final String registryMethod;
        final String configType;

        Kind(Class<?> annotation, String field, String policyType, String registryMethod, String configType) {
            this.annotation = annotation;
            this.field = field;
            this.policyType = policyType;
            this.registryMethod = registryMethod;
            this.configType = configType;
        }
    }

    private Elements elements;
    private Types types;
    private Messager messager;
    private final Map<Kind, PolicyAttributes> attributes = new EnumMap<>(Kind.class);
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
        for (Kind kind : Kind.values()) {
            attributes.put(kind, new PolicyAttributes(messager, kind.annotation));
        }
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
                for (PolicyAttributes policy : attributes.values()) {
                    if (PolicyAttributes.hasAnnotation(type, policy.annotation())
                            && !PolicyAttributes.hasAnnotation(type, PolicyAttributes.POLICY)) {
                        error(type, "@" + type.getSimpleName() + " uses @" + policy.simpleName()
                                + " but is not marked with @ResiliencePolicy, so its policies would be ignored."
                                + " Add @ResiliencePolicy to it.");
                    }
                }
            }
            case INTERFACE -> generateIfNeeded(type);
            default -> {
                if (usesAnyPolicy(type)) {
                    error(type, "Resilience annotations are only supported on interfaces for now. Declare the"
                            + " methods in an interface and wrap the implementation with Resilient.of(...).");
                }
            }
        }
    }

    private boolean usesAnyPolicy(Element element) {
        for (PolicyAttributes policy : attributes.values()) {
            if (policy.on(element).isPresent()) {
                return true;
            }
            if (element instanceof TypeElement type) {
                for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
                    if (policy.on(method).isPresent()) {
                        return true;
                    }
                }
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

        Map<Kind, Optional<Map<String, AnnotationValue>>> typeLevel = new EnumMap<>(Kind.class);
        attributes.forEach((kind, policy) -> typeLevel.put(kind, policy.on(iface)));
        List<MethodModel> methods = new ArrayList<>();
        boolean anyPolicy = false;
        for (ExecutableElement method : members(iface)) {
            ExecutableType type = (ExecutableType) types.asMemberOf((DeclaredType) iface.asType(), method);
            Map<Kind, PolicyModel> policies = new EnumMap<>(Kind.class);
            for (Kind kind : Kind.values()) {
                Optional<Map<String, AnnotationValue>> effective = effective(kind, iface, typeLevel.get(kind), method);
                if (effective.isEmpty()) {
                    continue;
                }
                anyPolicy = true;
                PolicyModel model = policyModel(kind, iface, method, type, effective.get());
                if (model == null) {
                    valid = false;
                } else {
                    policies.put(kind, model);
                }
            }
            if (!policies.isEmpty() && returnsCompletionStage(type)) {
                error(method, "Resilience annotations on methods that return a CompletionStage are not supported yet.");
                valid = false;
            }
            methods.add(new MethodModel(method, type, policies));
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
            if ((modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.PRIVATE)) && usesAnyPolicy(method)) {
                error(method, "Resilience annotations cannot be used on static or private methods:"
                        + " calls to them cannot be intercepted.");
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

    /** Merges one policy's attributes, lowest precedence first: declaring interface, wrapped interface, method. */
    private Optional<Map<String, AnnotationValue>> effective(Kind kind, TypeElement iface,
            Optional<Map<String, AnnotationValue>> typeLevel, ExecutableElement method) {
        PolicyAttributes policy = attributes.get(kind);
        TypeElement declaring = (TypeElement) method.getEnclosingElement();
        Optional<Map<String, AnnotationValue>> declaringLevel =
                declaring.equals(iface) ? Optional.empty() : policy.on(declaring);
        Optional<Map<String, AnnotationValue>> methodLevel = policy.on(method);
        if (declaringLevel.isEmpty() && typeLevel.isEmpty() && methodLevel.isEmpty()) {
            return Optional.empty();
        }
        Map<String, AnnotationValue> merged = new LinkedHashMap<>();
        declaringLevel.ifPresent(merged::putAll);
        typeLevel.ifPresent(merged::putAll);
        methodLevel.ifPresent(merged::putAll);
        return Optional.of(merged);
    }

    private boolean returnsCompletionStage(ExecutableType type) {
        TypeElement completionStage = elements.getTypeElement("java.util.concurrent.CompletionStage");
        return completionStage != null && types.isAssignable(types.erasure(type.getReturnType()),
                types.erasure(completionStage.asType()));
    }

    /** Validates the attributes and turns them into builder calls. Returns null after reporting errors. */
    private PolicyModel policyModel(Kind kind, TypeElement iface, ExecutableElement method, ExecutableType type,
            Map<String, AnnotationValue> values) {
        String annotation = "@" + kind.annotation.getSimpleName();
        boolean valid = true;
        String name = "";
        String config = "";
        List<String> overrides = new ArrayList<>();
        for (Map.Entry<String, AnnotationValue> entry : values.entrySet()) {
            String attribute = entry.getKey();
            Object value = entry.getValue().getValue();
            String label = annotation + "." + attribute;
            switch (attribute) {
                case "name" -> name = (String) value;
                case "value" -> { // @Timeout("2s")
                    try {
                        long nanos = Durations.parseNanos((String) value);
                        if (nanos == 0) {
                            valid = invalid(method, annotation + " must be positive, was \"" + value + "\"");
                        } else {
                            overrides.add("timeout(java.time.Duration.ofNanos(" + nanos + "L))");
                        }
                    } catch (IllegalArgumentException e) {
                        valid = invalid(method, annotation + "(\"" + value + "\") is not a valid duration: "
                                + e.getMessage() + ". Use " + Durations.GRAMMAR + ".");
                    }
                }
                case "config" -> config = (String) value;
                case "enabled" -> {
                    if (value.equals("true") || value.equals("false")) {
                        overrides.add("enabled(" + value + ")");
                    } else {
                        valid = invalid(method, label + " must be \"\", \"true\" or \"false\", was \"" + value + "\"");
                    }
                }
                case "classifier" -> valid = invalid(method, label + " is not supported yet.");
                case "retryOn", "abortOn", "recordOn", "ignoreOn" ->
                        overrides.add(attribute + "(java.util.List.of(" + classList(value) + "))");
                case "maxAttempts", "windowSize", "minimumCalls", "halfOpenCalls" -> {
                    int number = (Integer) value;
                    if (number < 1) {
                        valid = invalid(method, label + " must be at least 1, was " + number);
                    } else {
                        overrides.add(attribute + "(" + number + ")");
                    }
                }
                case "delay", "maxDelay", "maxDuration", "slowCallDuration", "waitInOpen", "maxWaitInHalfOpen" -> {
                    try {
                        long nanos = Durations.parseNanos((String) value);
                        overrides.add(attribute + "(java.time.Duration.ofNanos(" + nanos + "L))");
                    } catch (IllegalArgumentException e) {
                        valid = invalid(method, label + " = \"" + value + "\" is not a valid duration: "
                                + e.getMessage() + ". Use " + Durations.GRAMMAR + ".");
                    }
                }
                case "multiplier" -> {
                    double multiplier = (Double) value;
                    if (!(multiplier >= 1) || Double.isInfinite(multiplier)) {
                        valid = invalid(method, label + " must be a finite number >= 1, was " + multiplier);
                    } else {
                        overrides.add("multiplier(" + multiplier + ")");
                    }
                }
                case "failureRateThreshold", "slowCallRateThreshold" -> {
                    float rate = (Float) value;
                    if (!(rate > 0 && rate <= 100)) {
                        valid = invalid(method, label + " must be a percentage > 0 and <= 100, was " + rate);
                    } else {
                        overrides.add(attribute + "(" + rate + "f)");
                    }
                }
                case "backoff" -> overrides.add("backoff(" + kind.configType + ".Backoff." + constant(value) + ")");
                case "jitter" -> overrides.add("jitter(" + kind.configType + ".Jitter." + constant(value) + ")");
                case "windowType" -> overrides.add("windowType(" + kind.configType + ".WindowType." + constant(value) + ")");
                default -> valid = invalid(method, "Unknown attribute " + label
                        + ". The processor and the annotations jar have different versions.");
            }
        }
        if (!valid) {
            return null;
        }
        String instanceId = iface.getQualifiedName() + "#" + method.getSimpleName() + "(" + erasedParameters(type) + ")";
        return new PolicyModel(instanceId, name, config, overrides);
    }

    private void write(TypeElement iface, String binaryName, List<MethodModel> methods) {
        String packageName = elements.getPackageOf(iface).getQualifiedName().toString();
        String simpleName = (packageName.isEmpty() ? binaryName : binaryName.substring(packageName.length() + 1)) + SUFFIX;
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
        out.append("public final class ").append(simpleName).append(typeParameters(iface.getTypeParameters()))
                .append(" implements ").append(ifaceType).append(" {\n\n");
        out.append("    private final ").append(ifaceType).append(" delegate;\n");
        for (int i = 0; i < methods.size(); i++) {
            for (Kind kind : methods.get(i).policies().keySet()) {
                out.append("    private final ").append(kind.policyType).append(' ').append(kind.field).append(i).append(";\n");
            }
        }
        out.append("\n    public ").append(simpleName).append("(").append(ifaceType).append(" delegate, ")
                .append(REGISTRY_TYPE).append(" registry) {\n");
        out.append("        this.delegate = java.util.Objects.requireNonNull(delegate, \"delegate\");\n");
        out.append("        java.util.Objects.requireNonNull(registry, \"registry\");\n");
        for (int i = 0; i < methods.size(); i++) {
            for (Map.Entry<Kind, PolicyModel> entry : methods.get(i).policies().entrySet()) {
                Kind kind = entry.getKey();
                PolicyModel policy = entry.getValue();
                out.append("        this.").append(kind.field).append(i).append(" = registry.").append(kind.registryMethod)
                        .append('(').append(literal(policy.instanceId())).append(", ").append(literal(policy.name()))
                        .append(", ").append(literal(policy.config())).append(", ").append(overrides(policy.overrides()))
                        .append(");\n");
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
        String returnType = TypePrinter.print(type.getReturnType());
        boolean isVoid = type.getReturnType().getKind() == TypeKind.VOID;
        String typeVariables = type.getTypeVariables().isEmpty() ? "" : methodTypeVariables(type.getTypeVariables()) + " ";
        String throwsClause = type.getThrownTypes().isEmpty() ? "" : " throws " + TypePrinter.join(type.getThrownTypes(), ", ");
        String signatureTail = "(" + String.join(", ", parameters) + ")" + throwsClause;
        String args = String.join(", ", arguments);
        String delegateCall = "this.delegate." + method.getSimpleName() + "(" + args + ")";

        // One layer per policy, outermost first. Layer i calls layer i + 1; the last one calls the delegate.
        List<Kind> layers = new ArrayList<>(model.policies().keySet());
        List<String> layerNames = new ArrayList<>();
        for (Kind kind : layers) {
            layerNames.add("r4c$" + kind.field + index);
        }
        out.append("\n    @Override\n    public ").append(typeVariables).append(returnType).append(' ')
                .append(method.getSimpleName()).append(signatureTail).append(" {\n");
        if (layers.isEmpty()) {
            appendLines(out, List.of(isVoid ? delegateCall + ";" : "return " + delegateCall + ";"));
        } else {
            appendLines(out, layer(layers.get(0), index, inner(layerNames, 1, args, delegateCall), isVoid, returnType));
        }
        out.append("    }\n");
        for (int i = 1; i < layers.size(); i++) {
            out.append("\n    private ").append(typeVariables).append(returnType).append(' ').append(layerNames.get(i))
                    .append(signatureTail).append(" {\n");
            appendLines(out, layer(layers.get(i), index, inner(layerNames, i + 1, args, delegateCall), isVoid, returnType));
            out.append("    }\n");
        }
    }

    private static String inner(List<String> layerNames, int next, String args, String delegateCall) {
        return next < layerNames.size() ? "this." + layerNames.get(next) + "(" + args + ")" : delegateCall;
    }

    private static void appendLines(StringBuilder out, List<String> lines) {
        for (String line : lines) {
            out.append(line.isEmpty() ? "" : "        " + line).append('\n');
        }
    }

    private static List<String> layer(Kind kind, int index, String call, boolean isVoid, String returnType) {
        return switch (kind) {
            case RETRY -> retry(index, call, isVoid);
            case CIRCUIT_BREAKER -> circuitBreaker(index, call, isVoid, returnType);
            case TIMEOUT -> timeout(index, call, isVoid, returnType);
        };
    }

    private static List<String> retry(int index, String call, boolean isVoid) {
        List<String> lines = new ArrayList<>();
        lines.add("final " + Kind.RETRY.policyType + " r4c$retry = this." + Kind.RETRY.field + index + ";");
        lines.add("final long r4c$start = r4c$retry.start();");
        lines.add("int r4c$attempts = 0;");
        lines.add("while (true) {");
        lines.add("    try {");
        if (isVoid) {
            lines.add("        " + call + ";");
            lines.add("        return;");
        } else {
            lines.add("        return " + call + ";");
        }
        lines.add("    } catch (java.lang.Throwable r4c$e) {");
        lines.add("        final long r4c$delay = r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start);");
        lines.add("        if (r4c$delay < 0 || !r4c$retry.pause(r4c$delay)) {");
        lines.add("            throw r4c$e;");
        lines.add("        }");
        lines.add("    }");
        lines.add("}");
        return lines;
    }

    private static List<String> circuitBreaker(int index, String call, boolean isVoid, String returnType) {
        List<String> lines = new ArrayList<>();
        lines.add("final " + Kind.CIRCUIT_BREAKER.policyType + " r4c$cb = this." + Kind.CIRCUIT_BREAKER.field + index + ";");
        lines.add("final long r4c$token = r4c$cb.acquire();");
        lines.add("if (r4c$token < 0) {");
        lines.add("    throw r4c$cb.rejection();");
        lines.add("}");
        lines.add("final long r4c$started = r4c$cb.startTimer();");
        lines.add("try {");
        if (isVoid) {
            lines.add("    " + call + ";");
            lines.add("    r4c$cb.onSuccess(r4c$token, r4c$started);");
            lines.add("    return;");
        } else {
            lines.add("    final " + returnType + " r4c$result = " + call + ";");
            lines.add("    r4c$cb.onSuccess(r4c$token, r4c$started);");
            lines.add("    return r4c$result;");
        }
        lines.add("} catch (java.lang.Throwable r4c$e) {");
        lines.add("    r4c$cb.onFailure(r4c$token, r4c$started, r4c$e);");
        lines.add("    throw r4c$e;");
        lines.add("}");
        return lines;
    }

    private static List<String> timeout(int index, String call, boolean isVoid, String returnType) {
        List<String> lines = new ArrayList<>();
        lines.add("final " + Kind.TIMEOUT.policyType + " r4c$timeout = this." + Kind.TIMEOUT.field + index + ";");
        lines.add("final " + Kind.TIMEOUT.policyType + ".Scope r4c$scope = r4c$timeout.enter();");
        if (!isVoid) {
            lines.add("final " + returnType + " r4c$result;");
        }
        lines.add("try {");
        lines.add("    " + (isVoid ? "" : "r4c$result = ") + call + ";");
        lines.add("} catch (java.lang.Throwable r4c$e) {");
        lines.add("    r4c$timeout.failed(r4c$scope, r4c$e);");
        lines.add("    throw r4c$e;");
        lines.add("}");
        lines.add("r4c$timeout.succeeded(r4c$scope);");
        lines.add(isVoid ? "return;" : "return r4c$result;");
        return lines;
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
                    ? ((IntersectionType) bound).getBounds() : List.of(bound);
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

    private record MethodModel(ExecutableElement method, ExecutableType type, Map<Kind, PolicyModel> policies) {
    }

    private record PolicyModel(String instanceId, String name, String config, List<String> overrides) {
    }
}
