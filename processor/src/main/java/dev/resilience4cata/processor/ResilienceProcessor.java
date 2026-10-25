package dev.resilience4cata.processor;

import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.RateLimiter;
import dev.resilience4cata.annotation.Retry;
import dev.resilience4cata.annotation.Timeout;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
 * <p>Policies are nested in the default order, outermost first: Fallback, Retry, CircuitBreaker, RateLimiter,
 * Timeout, Bulkhead. Each attempt is recorded by the breaker, an open breaker stops the retries, calls
 * rejected by the breaker use no rate-limiter permit, the timeout
 * applies to each attempt (including the wait for a bulkhead slot), and a bulkhead slot is held only
 * while the method runs. The fallback sees the final outcome, rejections included; when the method also
 * retries, the fallback runs where the retry loop gives up, so that it knows the number of attempts. The public method holds the outermost layer; every inner layer is a private method
 * of the generated class, which the JIT inlines like any statically bound call.
 */
public final class ResilienceProcessor extends AbstractProcessor {

    private static final String SUFFIX = "$Resilient";
    private static final String REGISTRY_TYPE = "dev.resilience4cata.ResilienceRegistry";

    /** Supported policies, in nesting order: outermost first. */
    private enum Kind {
        FALLBACK(Fallback.class, "fallback", "dev.resilience4cata.generated.FallbackPolicy", "", ""),
        RETRY(Retry.class, "retry", "dev.resilience4cata.generated.RetryPolicy", "retryPolicy",
                "dev.resilience4cata.RetryConfig"),
        CIRCUIT_BREAKER(CircuitBreaker.class, "circuitBreaker", "dev.resilience4cata.generated.CircuitBreakerPolicy",
                "circuitBreakerPolicy", "dev.resilience4cata.CircuitBreakerConfig"),
        RATE_LIMITER(RateLimiter.class, "rateLimiter", "dev.resilience4cata.generated.RateLimiterPolicy",
                "rateLimiterPolicy", "dev.resilience4cata.RateLimiterConfig"),
        TIMEOUT(Timeout.class, "timeout", "dev.resilience4cata.generated.TimeoutPolicy", "timeoutPolicy",
                "dev.resilience4cata.TimeoutConfig"),
        BULKHEAD(Bulkhead.class, "bulkhead", "dev.resilience4cata.generated.BulkheadPolicy", "bulkheadPolicy",
                "dev.resilience4cata.BulkheadConfig");

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
    private SubclassWriter subclasses;
    /** What generated methods call: the wrapped instance for an interface, {@code super} for a class. */
    private String delegateReceiver = "this.delegate";

    /**
     * Whether {@link #process} claims the annotations it sees. javac's {@code -Xlint:processing} warns about
     * every annotation that no processor claimed, so a {@code "*"} processor that claims nothing makes
     * {@code -Xlint:all -Werror} builds fail. Claiming {@code "*"} would hide the annotations from processors
     * that run after this one, so it is only done when this is the only processor on the processor path.
     */
    private final boolean claimsAnnotations;

    /** Creates the processor. Called by the compiler through {@link java.util.ServiceLoader}. */
    public ResilienceProcessor() {
        this(aloneOnProcessorPath());
    }

    /** For tests: decides whether the processor claims the annotations it sees. */
    ResilienceProcessor(boolean claimsAnnotations) {
        this.claimsAnnotations = claimsAnnotations;
    }

    /** True when every processor registered next to this one is this one; see {@link #claimsAnnotations}. */
    private static boolean aloneOnProcessorPath() {
        try {
            // Lists the registered processors without instantiating them.
            List<String> names = java.util.ServiceLoader.load(javax.annotation.processing.Processor.class,
                            ResilienceProcessor.class.getClassLoader())
                    .stream().map(provider -> provider.type().getName()).toList();
            return aloneAmong(names);
        } catch (java.util.ServiceConfigurationError | LinkageError e) {
            return false; // unsure: stay cooperative
        }
    }

    /**
     * True when {@code names} (the registered processors) contains only this library's processors: this one
     * and {@link AnnotationClaimer}, which is registered next to it.
     */
    static boolean aloneAmong(List<String> names) {
        Set<String> ours = Set.of(ResilienceProcessor.class.getName(), AnnotationClaimer.class.getName());
        return names.contains(ResilienceProcessor.class.getName()) && ours.containsAll(names);
    }

    /** Whether {@link #process} claims the annotations it sees; see the field. */
    boolean claimsAnnotations() {
        return claimsAnnotations;
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elements = processingEnv.getElementUtils();
        types = processingEnv.getTypeUtils();
        messager = processingEnv.getMessager();
        subclasses = new SubclassWriter(elements, types, messager);
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
        return claimsAnnotations;
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
                        error(type, ProcessorError.BUNDLE_NOT_MARKED, "@" + type.getSimpleName() + " uses @" + policy.simpleName()
                                + " but is not marked with @ResiliencePolicy, so its policies would be ignored."
                                + " Add @ResiliencePolicy to it.");
                    }
                }
            }
            case INTERFACE -> generateIfNeeded(type);
            case CLASS -> {
                // A class opts in by declaring an annotation itself; interfaces and superclasses then add theirs.
                if (!usesAnyPolicy(type)) {
                    return;
                }
                if (subclasses.check(type, this::usesAnyPolicy)) {
                    generateIfNeeded(type);
                } else {
                    checkDeclaredMethods(type);
                }
            }
            default -> {
                if (usesAnyPolicy(type)) {
                    error(type, ProcessorError.ENUM_OR_RECORD, "Resilience annotations cannot be used on " + (type.getKind() == ElementKind.ENUM
                            ? "enums" : "records") + ": the generated subclass must extend the type, and "
                            + (type.getKind() == ElementKind.ENUM ? "enums" : "records") + " are final. Declare the"
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
            FallbackModel fallback = null;
            TypeMirror asyncResult = asyncResult(type);
            for (Kind kind : Kind.values()) {
                Optional<Map<String, AnnotationValue>> effective = effective(kind, iface, typeLevel.get(kind), method);
                if (effective.isEmpty()) {
                    continue;
                }
                anyPolicy = true;
                if (kind == Kind.FALLBACK) {
                    fallback = asyncResult != null && asyncResult.getKind() == TypeKind.NONE ? null
                            : fallbackModel(iface, method, type, asyncResult, effective.get());
                    valid &= fallback != null;
                    continue;
                }
                PolicyModel model = policyModel(kind, iface, method, type, asyncResult, effective.get());
                if (model == null) {
                    valid = false;
                } else {
                    policies.put(kind, model);
                }
            }
            boolean annotated = !policies.isEmpty() || fallback != null || effective(Kind.FALLBACK, iface,
                    typeLevel.get(Kind.FALLBACK), method).isPresent();
            if (annotated && asyncResult != null && asyncResult.getKind() == TypeKind.NONE) {
                error(method, ProcessorError.UNSUPPORTED_ASYNC_TYPE, "An asynchronous method must return CompletionStage<T> or CompletableFuture<T>, where T"
                        + " may also be ? or ? extends X (not ? super X), to use resilience annotations; it returns "
                        + TypePrinter.print(type.getReturnType()) + ".");
                valid = false;
            }
            if (annotated || iface.getKind() == ElementKind.INTERFACE) { // a subclass overrides only protected methods
                methods.add(new MethodModel(method, type, policies, fallback,
                    asyncResult == null || asyncResult.getKind() == TypeKind.NONE ? null : asyncResult));
            }
        }
        if (!anyPolicy || !valid) {
            return;
        }
        if (iface.getModifiers().contains(Modifier.PRIVATE)) {
            error(iface, ProcessorError.PRIVATE_INTERFACE, "Resilience annotations need a non-private interface: the generated class must implement it."
                    + " Make " + iface.getSimpleName() + " package-private or public.");
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
                error(method, ProcessorError.STATIC_OR_PRIVATE_METHOD, "Resilience annotations cannot be used on static or private methods:"
                        + " calls to them cannot be intercepted. Annotate a non-private instance method instead.");
                valid = false;
            }
        }
        return valid;
    }

    /** Every method the wrapper must implement, including inherited and default methods. */
    private List<ExecutableElement> members(TypeElement iface) {
        if (iface.getKind() == ElementKind.CLASS) {
            return subclasses.members(iface);
        }
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
        if (iface.getKind() == ElementKind.CLASS) { // interface < interface method < class < class method
            return subclasses.effective(policy, iface, method);
        }
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

    /**
     * For a method that returns {@code CompletionStage<T>} or {@code CompletableFuture<T>}, returns
     * {@code T}; for {@code ? extends X}, returns {@code X}, and for {@code ?}, {@code Object}: the policies
     * then work with the wildcard's upper bound (see {@link #isWildcardStage}). Returns null for a synchronous
     * method, and a {@code NONE} type for a stage type that is not supported: another {@code CompletionStage}
     * subtype, a raw type, or a {@code ? super X} argument.
     */
    private TypeMirror asyncResult(ExecutableType type) {
        TypeMirror returnType = type.getReturnType();
        TypeElement completionStage = elements.getTypeElement("java.util.concurrent.CompletionStage");
        if (returnType.getKind() != TypeKind.DECLARED
                || !types.isAssignable(types.erasure(returnType), types.erasure(completionStage.asType()))) {
            return null;
        }
        TypeElement declared = (TypeElement) ((DeclaredType) returnType).asElement();
        List<? extends TypeMirror> arguments = ((DeclaredType) returnType).getTypeArguments();
        String name = declared.getQualifiedName().toString();
        if ((name.equals("java.util.concurrent.CompletionStage") || name.equals("java.util.concurrent.CompletableFuture"))
                && arguments.size() == 1) {
            if (arguments.get(0).getKind() != TypeKind.WILDCARD) {
                return arguments.get(0);
            }
            javax.lang.model.type.WildcardType wildcard = (javax.lang.model.type.WildcardType) arguments.get(0);
            if (wildcard.getSuperBound() == null) {
                return wildcard.getExtendsBound() != null ? wildcard.getExtendsBound()
                        : elements.getTypeElement("java.lang.Object").asType();
            }
        }
        return types.getNoType(TypeKind.NONE);
    }

    /** True if an asynchronous method returns a stage with a wildcard argument, {@code ? extends X} or {@code ?}. */
    private static boolean isWildcardStage(ExecutableType type) {
        List<? extends TypeMirror> arguments = ((DeclaredType) type.getReturnType()).getTypeArguments();
        return arguments.get(0).getKind() == TypeKind.WILDCARD;
    }

    /** {@code CompletionStage<? extends result>}: what a stage-returning fallback of a wildcard method may return. */
    private TypeMirror wildcardStageOf(TypeMirror result) {
        return types.getDeclaredType(elements.getTypeElement("java.util.concurrent.CompletionStage"),
                types.getWildcardType(result, null));
    }

    private TypeMirror stageOf(TypeMirror result) {
        return types.getDeclaredType(elements.getTypeElement("java.util.concurrent.CompletionStage"), result);
    }

    /** Validates the attributes and turns them into builder calls. Returns null after reporting errors. */
    private PolicyModel policyModel(Kind kind, TypeElement iface, ExecutableElement method, ExecutableType type,
            TypeMirror asyncResult, Map<String, AnnotationValue> values) {
        String annotation = "@" + kind.annotation.getSimpleName();
        boolean valid = true;
        String name = "";
        String config = "";
        String classifier = null;
        boolean throwOnExhaustedResult = false;
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
                            valid = invalid(method, ProcessorError.DURATION_NOT_POSITIVE, annotation + " must be positive, was \"" + value + "\"");
                        } else {
                            overrides.add("timeout(java.time.Duration.ofNanos(" + nanos + "L))");
                        }
                    } catch (IllegalArgumentException e) {
                        valid = invalid(method, ProcessorError.INVALID_DURATION, annotation + "(\"" + value + "\") is not a valid duration: "
                                + e.getMessage() + ". Use " + Durations.GRAMMAR + ".");
                    }
                }
                case "config" -> config = (String) value;
                case "enabled" -> {
                    if (value.equals("true") || value.equals("false")) {
                        overrides.add("enabled(" + value + ")");
                    } else {
                        valid = invalid(method, ProcessorError.INVALID_ENABLED, label + " must be \"\", \"true\" or \"false\", was \"" + value + "\"");
                    }
                }
                case "classifier" -> {
                    if (!(value instanceof TypeMirror classifierType)) {
                        valid = false; // the compiler already reported the unresolvable class
                        continue;
                    }
                    classifier = classifier(iface, method, type, asyncResult, label, classifierType);
                    valid &= classifier != null;
                }
                case "onExhaustedResult" -> throwOnExhaustedResult = constant(value).equals("THROW");
                case "retryOn", "abortOn", "recordOn", "ignoreOn" ->
                        overrides.add(attribute + "(java.util.List.of(" + classList(value) + "))");
                case "maxAttempts", "windowSize", "minimumCalls", "halfOpenCalls", "maxConcurrent", "permits" -> {
                    int number = (Integer) value;
                    if (number < 1) {
                        valid = invalid(method, ProcessorError.COUNT_BELOW_ONE, label + " must be at least 1, was " + number);
                    } else {
                        overrides.add(attribute + "(" + number + ")");
                    }
                }
                case "delay", "maxDelay", "maxDuration", "slowCallDuration", "waitInOpen", "maxWaitInHalfOpen", "maxWait" -> {
                    try {
                        long nanos = Durations.parseNanos((String) value);
                        overrides.add(attribute + "(java.time.Duration.ofNanos(" + nanos + "L))");
                    } catch (IllegalArgumentException e) {
                        valid = invalid(method, ProcessorError.INVALID_DURATION, label + " = \"" + value + "\" is not a valid duration: "
                                + e.getMessage() + ". Use " + Durations.GRAMMAR + ".");
                    }
                }
                case "period" -> {
                    try {
                        long nanos = Durations.parseNanos((String) value);
                        if (nanos == 0) {
                            valid = invalid(method, ProcessorError.DURATION_NOT_POSITIVE, label + " must be positive, was \"" + value + "\"");
                        } else {
                            overrides.add("period(java.time.Duration.ofNanos(" + nanos + "L))");
                        }
                    } catch (IllegalArgumentException e) {
                        valid = invalid(method, ProcessorError.INVALID_DURATION, label + " = \"" + value + "\" is not a valid duration: "
                                + e.getMessage() + ". Use " + Durations.GRAMMAR + ".");
                    }
                }
                case "multiplier" -> {
                    double multiplier = (Double) value;
                    if (!(multiplier >= 1) || Double.isInfinite(multiplier)) {
                        valid = invalid(method, ProcessorError.INVALID_MULTIPLIER, label + " must be a finite number >= 1, was " + multiplier);
                    } else {
                        overrides.add("multiplier(" + multiplier + ")");
                    }
                }
                case "failureRateThreshold", "slowCallRateThreshold" -> {
                    float rate = (Float) value;
                    if (!(rate > 0 && rate <= 100)) {
                        valid = invalid(method, ProcessorError.INVALID_PERCENTAGE, label + " must be a percentage > 0 and <= 100, was " + rate);
                    } else {
                        overrides.add(attribute + "(" + rate + "f)");
                    }
                }
                case "backoff" -> overrides.add("backoff(" + kind.configType + ".Backoff." + constant(value) + ")");
                case "jitter" -> overrides.add("jitter(" + kind.configType + ".Jitter." + constant(value) + ")");
                case "windowType" -> overrides.add("windowType(" + kind.configType + ".WindowType." + constant(value) + ")");
                default -> valid = invalid(method, ProcessorError.UNKNOWN_ATTRIBUTE, "Unknown attribute " + label
                        + ". The processor and the annotations jar have different versions.");
            }
        }
        if (classifier != null) {
            String replaced = kind == Kind.RETRY ? "retryOn" : "recordOn";
            if (values.containsKey(replaced)) {
                valid = invalid(method, ProcessorError.CLASSIFIER_WITH_FILTER, annotation + "." + replaced + " cannot be combined with " + annotation
                        + ".classifier: the classifier decides which exceptions count as failures. Remove "
                        + replaced + ", or return Outcome.FAILURE for those exceptions from the classifier. ("
                        + (kind == Kind.RETRY ? "abortOn" : "ignoreOn") + " still applies before the classifier.)");
            }
        }
        if (values.containsKey("onExhaustedResult") && !values.containsKey("classifier")) {
            valid = invalid(method, ProcessorError.EXHAUSTED_WITHOUT_CLASSIFIER, annotation + ".onExhaustedResult needs " + annotation + ".classifier: only a"
                    + " classifier can reject a result. Add a classifier, or remove onExhaustedResult.");
        }
        if (!valid) {
            return null;
        }
        String instanceId = iface.getQualifiedName() + "#" + method.getSimpleName() + "(" + erasedParameters(type) + ")";
        return new PolicyModel(instanceId, name, config, overrides, classifier, throwOnExhaustedResult);
    }

    /**
     * Checks a {@code classifier} class: a concrete, non-generic class with an accessible no-argument
     * constructor that implements {@code Classifier<X>}, where the method's value type is assignable to
     * {@code X}. {@code asyncResult} is the {@code T} of an asynchronous method, or null. Returns the
     * class's qualified name, or null after reporting an error.
     */
    private String classifier(TypeElement iface, ExecutableElement method, ExecutableType type, TypeMirror asyncResult,
            String attribute, TypeMirror classifier) {
        String label = attribute + " = " + TypePrinter.print(classifier) + ".class";
        TypeElement classifierType = elements.getTypeElement("dev.resilience4cata.Classifier");
        if (classifierType == null) {
            return invalidClassifier(method, ProcessorError.CLASSIFIER_MISSING_CORE, label + ": dev.resilience4cata.Classifier is not on the classpath. Add the"
                    + " resilience4cata-core dependency.");
        }
        if (classifier.getKind() != TypeKind.DECLARED
                || ((DeclaredType) classifier).asElement().getKind() != ElementKind.CLASS) {
            return invalidClassifier(method, ProcessorError.CLASSIFIER_NOT_CLASS, label + ": the classifier must be a class that implements"
                    + " dev.resilience4cata.Classifier<T>.");
        }
        TypeElement classifierClass = (TypeElement) ((DeclaredType) classifier).asElement();
        if (classifierClass.getModifiers().contains(Modifier.ABSTRACT)) {
            return invalidClassifier(method, ProcessorError.CLASSIFIER_ABSTRACT, label + ": the classifier must not be abstract, because one instance is"
                    + " created per method.");
        }
        if (!classifierClass.getTypeParameters().isEmpty()) {
            return invalidClassifier(method, ProcessorError.CLASSIFIER_GENERIC, label + ": generic classifier classes are not supported. Implement"
                    + " Classifier<T> with a concrete T.");
        }
        String samePackage = elements.getPackageOf(iface).getQualifiedName().toString();
        for (Element e = classifierClass; e instanceof TypeElement t; e = t.getEnclosingElement()) {
            if (!accessible(t, samePackage)) {
                return invalidClassifier(method, ProcessorError.CLASSIFIER_INACCESSIBLE, label + ": " + t.getSimpleName() + " must be public, or package-private"
                        + " in package " + samePackage + ", for the generated class to create the classifier.");
            }
        }
        if (classifierClass.getNestingKind().isNested() && !classifierClass.getModifiers().contains(Modifier.STATIC)) {
            return invalidClassifier(method, ProcessorError.CLASSIFIER_INNER, label + ": a nested classifier class must be static.");
        }
        boolean constructor = false;
        for (ExecutableElement c : ElementFilter.constructorsIn(classifierClass.getEnclosedElements())) {
            if (c.getParameters().isEmpty() && accessible(c, classifierClass, samePackage)) {
                constructor = true;
            }
        }
        if (!constructor) {
            return invalidClassifier(method, ProcessorError.CLASSIFIER_NO_CONSTRUCTOR, label + ": the classifier needs a public no-argument constructor.");
        }
        List<? extends TypeMirror> arguments = supertypeArguments(classifier, types.erasure(classifierType.asType()));
        if (arguments == null) {
            return invalidClassifier(method, ProcessorError.CLASSIFIER_NOT_CLASSIFIER, label + ": " + classifierClass.getSimpleName()
                    + " does not implement dev.resilience4cata.Classifier<T>.");
        }
        TypeMirror value = asyncResult != null ? asyncResult : boxed(type.getReturnType());
        if (arguments.isEmpty()) {
            return invalidClassifier(method, ProcessorError.CLASSIFIER_RAW, label + ": " + classifierClass.getSimpleName() + " implements the raw type"
                    + " Classifier. Implement Classifier<" + TypePrinter.print(value) + "> instead.");
        }
        TypeMirror classified = arguments.get(0);
        if (value.getKind() != TypeKind.NONE && !types.isAssignable(value, classified)) {
            return invalidClassifier(method, ProcessorError.CLASSIFIER_WRONG_TYPE, label + ": " + classifierClass.getSimpleName() + " implements Classifier<"
                    + TypePrinter.print(classified) + ">, but " + method.getSimpleName() + " "
                    + (asyncResult != null ? "completes with " : "returns ") + TypePrinter.print(value)
                    + ". Implement Classifier<" + TypePrinter.print(value) + "> or a supertype of it.");
        }
        return classifierClass.getQualifiedName().toString();
    }

    private String invalidClassifier(Element element, ProcessorError error, String message) {
        error(element, error, message);
        return null;
    }

    /** Validates a {@code @Fallback}. Returns null after reporting errors. */
    /** {@code asyncResult} is the {@code T} of an asynchronous method, or null for a synchronous one. */
    private FallbackModel fallbackModel(TypeElement iface, ExecutableElement method, ExecutableType type,
            TypeMirror asyncResult, Map<String, AnnotationValue> values) {
        boolean valid = true;
        TypeMirror handler = null;
        String methodName = "";
        String applyOn = "java.lang.Throwable.class";
        String skipOn = "";
        for (Map.Entry<String, AnnotationValue> entry : values.entrySet()) {
            Object value = entry.getValue().getValue();
            switch (entry.getKey()) {
                case "value" -> handler = (TypeMirror) value;
                case "method" -> methodName = (String) value;
                case "applyOn" -> applyOn = classList(value);
                case "skipOn" -> skipOn = classList(value);
                default -> valid = invalid(method, ProcessorError.UNKNOWN_ATTRIBUTE, "Unknown attribute @Fallback." + entry.getKey()
                        + ". The processor and the annotations jar have different versions.");
            }
        }
        if ((handler == null) == methodName.isEmpty()) {
            return invalidModel(method, ProcessorError.FALLBACK_TARGET, "@Fallback needs exactly one of a handler class, @Fallback(MyFallback.class),"
                    + " or a sibling method, @Fallback(method = \"name\").");
        }
        if (!valid) {
            return null;
        }
        String instanceId = iface.getQualifiedName() + "#" + method.getSimpleName() + "(" + erasedParameters(type) + ")";
        List<String> checkedThrown = new ArrayList<>();
        for (TypeMirror thrown : type.getThrownTypes()) {
            if (isChecked(thrown)) {
                checkedThrown.add(TypePrinter.print(thrown));
            }
        }
        if (handler != null) {
            Handler checked = handler(iface, method, type, asyncResult, handler);
            return checked == null ? null : new FallbackModel(instanceId, applyOn, skipOn, checked.name(),
                    checked.returnsValue(), List.of(), checkedThrown, checked.widens());
        }
        List<FallbackTarget> targets = fallbackTargets(iface, method, type, asyncResult, methodName);
        return targets == null ? null
                : new FallbackModel(instanceId, applyOn, skipOn, null, true, targets, checkedThrown, false);
    }

    /**
     * A checked handler class. {@code returnsValue}: for an async method, it returns {@code T} rather than a stage.
     * {@code widens}: it returns a {@code CompletionStage<? extends T>}, which the generated code widens.
     */
    private record Handler(String name, boolean returnsValue, boolean widens) {
    }

    /** Checks a handler class. Returns null after reporting an error. */
    private Handler handler(TypeElement iface, ExecutableElement method, ExecutableType type, TypeMirror asyncResult,
            TypeMirror handler) {
        String label = "@Fallback(" + TypePrinter.print(handler) + ".class)";
        TypeElement fallbackType = elements.getTypeElement("dev.resilience4cata.FallbackHandler");
        if (fallbackType == null) {
            return invalidHandler(method, ProcessorError.HANDLER_MISSING_CORE, label + ": dev.resilience4cata.FallbackHandler is not on the classpath. Add the"
                    + " resilience4cata-core dependency.");
        }
        if (handler.getKind() != TypeKind.DECLARED
                || ((DeclaredType) handler).asElement().getKind() != ElementKind.CLASS) {
            return invalidHandler(method, ProcessorError.HANDLER_NOT_CLASS, label + ": the handler must be a class that implements FallbackHandler<T>.");
        }
        TypeElement handlerClass = (TypeElement) ((DeclaredType) handler).asElement();
        if (handlerClass.getModifiers().contains(Modifier.ABSTRACT)) {
            return invalidHandler(method, ProcessorError.HANDLER_ABSTRACT, label + ": the handler must not be abstract, because one instance is created per method.");
        }
        if (!handlerClass.getTypeParameters().isEmpty()) {
            return invalidHandler(method, ProcessorError.HANDLER_GENERIC, label + ": generic handler classes are not supported. Implement FallbackHandler<T> with a concrete T.");
        }
        String samePackage = elements.getPackageOf(iface).getQualifiedName().toString();
        for (Element e = handlerClass; e instanceof TypeElement t; e = t.getEnclosingElement()) {
            if (!accessible(t, samePackage)) {
                return invalidHandler(method, ProcessorError.HANDLER_INACCESSIBLE, label + ": " + t.getSimpleName() + " must be public, or package-private in"
                        + " package " + samePackage + ", for the generated class to create the handler.");
            }
        }
        if (handlerClass.getNestingKind().isNested() && !handlerClass.getModifiers().contains(Modifier.STATIC)) {
            return invalidHandler(method, ProcessorError.HANDLER_INNER, label + ": a nested handler class must be static.");
        }
        boolean constructor = false;
        for (ExecutableElement c : ElementFilter.constructorsIn(handlerClass.getEnclosedElements())) {
            if (c.getParameters().isEmpty() && accessible(c, handlerClass, samePackage)) {
                constructor = true;
            }
        }
        if (!constructor) {
            return invalidHandler(method, ProcessorError.HANDLER_NO_CONSTRUCTOR, label + ": the handler needs a public no-argument constructor.");
        }
        List<? extends TypeMirror> arguments = supertypeArguments(handler, types.erasure(fallbackType.asType()));
        if (arguments == null) {
            return invalidHandler(method, ProcessorError.HANDLER_NOT_HANDLER, label + ": " + handlerClass.getSimpleName() + " does not implement dev.resilience4cata.FallbackHandler<T>.");
        }
        if (arguments.isEmpty()) {
            return invalidHandler(method, ProcessorError.HANDLER_RAW, label + ": " + handlerClass.getSimpleName() + " implements the raw type Fallback."
                    + " Implement FallbackHandler<" + TypePrinter.print(boxed(type.getReturnType())) + "> instead.");
        }
        TypeMirror result = arguments.get(0);
        TypeMirror returnType = type.getReturnType();
        String name = handlerClass.getQualifiedName().toString();
        if (asyncResult != null) {
            if (types.isAssignable(result, stageOf(asyncResult))) {
                return new Handler(name, false, false);
            }
            if (types.isAssignable(result, wildcardStageOf(asyncResult))) {
                return new Handler(name, false, true);
            }
            if (types.isAssignable(result, asyncResult)) {
                return new Handler(name, true, false);
            }
            if (mentions(asyncResult, type.getTypeVariables())) {
                return invalidHandler(method, ProcessorError.HANDLER_ON_GENERIC_METHOD, genericHandler(label, handlerClass, result, method, returnType));
            }
            return invalidHandler(method, ProcessorError.HANDLER_WRONG_TYPE, label + ": " + handlerClass.getSimpleName() + " implements FallbackHandler<"
                    + TypePrinter.print(result) + ">, but " + method.getSimpleName() + " returns "
                    + TypePrinter.print(returnType) + ". Implement FallbackHandler<" + TypePrinter.print(asyncResult)
                    + "> or FallbackHandler<java.util.concurrent.CompletionStage<" + TypePrinter.print(asyncResult) + ">>.");
        }
        if (returnType.getKind() != TypeKind.VOID && !types.isAssignable(result, returnType)
                && mentions(returnType, type.getTypeVariables())) {
            return invalidHandler(method, ProcessorError.HANDLER_ON_GENERIC_METHOD, genericHandler(label, handlerClass, result, method, returnType));
        }
        if (returnType.getKind() != TypeKind.VOID && !types.isAssignable(result, returnType)) {
            return invalidHandler(method, ProcessorError.HANDLER_WRONG_TYPE, label + ": " + handlerClass.getSimpleName() + " implements FallbackHandler<"
                    + TypePrinter.print(result) + ">, but " + method.getSimpleName() + " returns "
                    + TypePrinter.print(returnType) + ". Implement FallbackHandler<" + TypePrinter.print(boxed(returnType)) + ">.");
        }
        return new Handler(name, true, false);
    }

    /**
     * A handler implements {@code FallbackHandler<X>} for one {@code X}, but a generic method's result type
     * changes with every call, and no {@code X} fits every instantiation without an unchecked cast.
     */
    private static String genericHandler(String label, TypeElement handlerClass, TypeMirror result,
            ExecutableElement method, TypeMirror returnType) {
        return label + ": " + handlerClass.getSimpleName() + " implements FallbackHandler<" + TypePrinter.print(result)
                + ">, but the generic method " + method.getSimpleName() + " returns " + TypePrinter.print(returnType)
                + ", which depends on its type parameters: a handler cannot produce a value of every such type."
                + " Use a sibling fallback method with the same type parameters, @Fallback(method = \""
                + method.getSimpleName() + "\").";
    }

    /** True if {@code type} uses any of {@code variables}. */
    private static boolean mentions(TypeMirror type, List<? extends TypeVariable> variables) {
        return switch (type.getKind()) {
            case TYPEVAR -> variables.stream().anyMatch(v -> v.asElement().equals(((TypeVariable) type).asElement()));
            case ARRAY -> mentions(((javax.lang.model.type.ArrayType) type).getComponentType(), variables);
            case WILDCARD -> {
                javax.lang.model.type.WildcardType wildcard = (javax.lang.model.type.WildcardType) type;
                yield (wildcard.getExtendsBound() != null && mentions(wildcard.getExtendsBound(), variables))
                        || (wildcard.getSuperBound() != null && mentions(wildcard.getSuperBound(), variables));
            }
            case DECLARED -> ((DeclaredType) type).getTypeArguments().stream().anyMatch(a -> mentions(a, variables))
                    || mentions(((DeclaredType) type).getEnclosingType(), variables);
            default -> false;
        };
    }

    private TypeMirror boxed(TypeMirror type) {
        if (type.getKind().isPrimitive()) {
            return types.boxedClass((javax.lang.model.type.PrimitiveType) type).asType();
        }
        return type.getKind() == TypeKind.VOID ? elements.getTypeElement("java.lang.Void").asType() : type;
    }

    private static boolean accessible(TypeElement type, String samePackage) {
        Set<Modifier> modifiers = type.getModifiers();
        if (modifiers.contains(Modifier.PRIVATE)) {
            return false;
        }
        return modifiers.contains(Modifier.PUBLIC) || packageOf(type).equals(samePackage);
    }

    private static boolean accessible(ExecutableElement constructor, TypeElement owner, String samePackage) {
        Set<Modifier> modifiers = constructor.getModifiers();
        if (modifiers.contains(Modifier.PRIVATE)) {
            return false;
        }
        return modifiers.contains(Modifier.PUBLIC) || packageOf(owner).equals(samePackage);
    }

    private static String packageOf(Element element) {
        Element e = element;
        while (e.getKind() != ElementKind.PACKAGE) {
            e = e.getEnclosingElement();
        }
        return ((javax.lang.model.element.PackageElement) e).getQualifiedName().toString();
    }

    /**
     * Type arguments with which {@code type} implements {@code erasedTarget}: null if it does not,
     * empty if it implements the raw type.
     */
    private List<? extends TypeMirror> supertypeArguments(TypeMirror type, TypeMirror erasedTarget) {
        for (TypeMirror supertype : types.directSupertypes(type)) {
            if (types.isSameType(types.erasure(supertype), erasedTarget)) {
                return ((DeclaredType) supertype).getTypeArguments();
            }
            List<? extends TypeMirror> found = supertypeArguments(supertype, erasedTarget);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Finds the sibling fallback methods, most specific exception first. Returns null after reporting an error. */
    private List<FallbackTarget> fallbackTargets(TypeElement iface, ExecutableElement method, ExecutableType type,
            TypeMirror asyncResult, String name) {
        List<? extends TypeMirror> parameters = type.getParameterTypes();
        TypeMirror throwable = elements.getTypeElement("java.lang.Throwable").asType();
        TypeMirror returnType = type.getReturnType();
        List<FallbackTarget> targets = new ArrayList<>();
        Map<FallbackTarget, TypeMirror> exceptionTypes = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Set<String> names = new TreeSet<>(); // every method that could be a fallback, for "did you mean"
        boolean named = false;
        for (ExecutableElement candidate : ElementFilter.methodsIn(elements.getAllMembers(iface))) {
            if (candidate.equals(method) || (candidate.getEnclosingElement().getKind() != ElementKind.INTERFACE
                    && (iface.getKind() != ElementKind.CLASS || isObject(candidate.getEnclosingElement())))) {
                continue;
            }
            names.add(candidate.getSimpleName().toString());
            if (!candidate.getSimpleName().contentEquals(name)) {
                continue;
            }
            named = true;
            ExecutableType candidateType = (ExecutableType) types.asMemberOf((DeclaredType) iface.asType(), candidate);
            List<? extends TypeMirror> candidateParameters = candidateType.getParameterTypes();
            int n = parameters.size();
            if (candidateParameters.size() != n && candidateParameters.size() != n + 1) {
                continue;
            }
            String signature = methodTypeVariables(candidateType.getTypeVariables(), "") + name + "("
                    + TypePrinter.join(candidateParameters, ", ") + ")";
            // A generic method's fallback declares the same type parameters, in the same order (names may differ).
            Map<Element, TypeMirror> renaming = typeParameterRenaming(type, candidateType);
            if (renaming == null) {
                if (!type.getTypeVariables().isEmpty() || !candidateType.getTypeVariables().isEmpty()) {
                    problems.add(signature + " does not declare the same type parameters as " + method.getSimpleName()
                            + ": " + (type.getTypeVariables().isEmpty() ? "none" : methodTypeVariables(type.getTypeVariables()))
                            + ".");
                }
                continue;
            }
            boolean sameParameters = true;
            for (int i = 0; i < n; i++) {
                sameParameters &= types.isSameType(substitute(candidateParameters.get(i), renaming), parameters.get(i));
            }
            TypeMirror exception = candidateParameters.size() == n + 1 ? substitute(candidateParameters.get(n), renaming) : null;
            if (!sameParameters || (exception != null && !types.isAssignable(exception, throwable))) {
                continue;
            }
            if (exception != null && exception.getKind() != TypeKind.DECLARED) {
                problems.add(signature + " takes a " + TypePrinter.print(exception) + " as its exception parameter, which"
                        + " is not a class: the generated code cannot test a failure against it.");
                continue;
            }
            Set<Modifier> modifiers = candidate.getModifiers();
            if (modifiers.contains(Modifier.PRIVATE)) {
                problems.add(signature + " is private, so the generated class cannot call it. Make it "
                        + (iface.getKind() == ElementKind.CLASS ? "package-private, protected or public." : "a default"
                        + " or static method."));
                continue;
            }
            if (iface.getKind() == ElementKind.CLASS && !modifiers.contains(Modifier.PUBLIC)
                    && !modifiers.contains(Modifier.PROTECTED) && !packageOf(candidate).equals(packageOf(iface))) {
                problems.add(signature + " is package-private in another package, so the generated class cannot call"
                        + " it. Make it protected or public.");
                continue;
            }
            TypeMirror candidateReturn = substitute(candidateType.getReturnType(), renaming);
            boolean returnsValue = true;
            boolean widens = false;
            if (asyncResult != null) {
                // An async fallback may return the value or a stage of it; it may throw anything, which fails the future.
                returnsValue = !types.isAssignable(candidateReturn, stageOf(asyncResult));
                if (returnsValue && types.isAssignable(candidateReturn, wildcardStageOf(asyncResult))) {
                    returnsValue = false;
                    widens = true;
                }
                if (returnsValue && !types.isAssignable(candidateReturn, asyncResult)) {
                    problems.add(signature + " returns " + TypePrinter.print(candidateReturn) + ", which is neither "
                            + TypePrinter.print(asyncResult) + " nor a CompletionStage of it.");
                    continue;
                }
            } else if (returnType.getKind() == TypeKind.VOID ? candidateReturn.getKind() != TypeKind.VOID
                    : !types.isAssignable(candidateReturn, returnType)) {
                problems.add(signature + " returns " + TypePrinter.print(candidateReturn) + ", which is not "
                        + (returnType.getKind() == TypeKind.VOID ? "void" : "assignable to " + TypePrinter.print(returnType)) + ".");
                continue;
            }
            List<String> undeclared = new ArrayList<>();
            for (TypeMirror candidateThrown : candidateType.getThrownTypes()) {
                TypeMirror thrown = substitute(candidateThrown, renaming);
                if (asyncResult == null && isChecked(thrown) && type.getThrownTypes().stream().noneMatch(t -> types.isAssignable(thrown, t))) {
                    undeclared.add(TypePrinter.print(thrown));
                }
            }
            if (!undeclared.isEmpty()) {
                problems.add(signature + " throws " + String.join(", ", undeclared) + ", which "
                        + method.getSimpleName() + " does not declare.");
                continue;
            }
            String receiver = modifiers.contains(Modifier.STATIC)
                    ? ((TypeElement) candidate.getEnclosingElement()).getQualifiedName().toString()
                    : iface.getKind() == ElementKind.CLASS ? "this" : "this.delegate";
            boolean catchesAll = exception == null || types.isSameType(exception, throwable);
            // Explicit type arguments, so that the call does not depend on inference.
            String typeArguments = type.getTypeVariables().isEmpty() ? ""
                    : "<" + TypePrinter.join(type.getTypeVariables(), ", ") + ">";
            FallbackTarget target = new FallbackTarget(receiver, typeArguments + name,
                    exception == null ? null : TypePrinter.print(exception), catchesAll, returnsValue, widens);
            targets.add(target);
            exceptionTypes.put(target, exception);
        }
        if (targets.isEmpty()) {
            String parameterList = TypePrinter.join(parameters, ", ");
            String expected = (type.getTypeVariables().isEmpty() ? "" : methodTypeVariables(type.getTypeVariables()) + " ")
                    + (returnType.getKind() == TypeKind.VOID ? "void" : TypePrinter.print(returnType)) + " "
                    + name + "(" + parameterList + (parameterList.isEmpty() ? "" : ", ") + "<a Throwable type> e)";
            String expectation = "Expected a " + (iface.getKind() == ElementKind.CLASS ? "non-private" : "default or static")
                    + " method " + expected + ", where the exception parameter is optional.";
            if (!named) {
                String closest = Suggestions.closest(name, names);
                error(method, ProcessorError.FALLBACK_METHOD_NOT_FOUND, "@Fallback(method = \"" + name + "\") on "
                        + method.getSimpleName() + ": no method named " + name + " in " + iface.getSimpleName() + "."
                        + (closest == null ? "" : " Did you mean " + closest + "?") + " " + expectation);
                return null;
            }
            StringBuilder message = new StringBuilder("@Fallback(method = \"" + name + "\"): no usable method " + name
                    + " in " + iface.getSimpleName() + ". " + expectation);
            for (String problem : problems) {
                message.append("\n  ").append(problem);
            }
            error(method, ProcessorError.FALLBACK_METHOD_UNUSABLE, message.toString());
            return null;
        }
        // Most specific first: a subtype is always deeper in the hierarchy. Methods without an exception parameter go last.
        targets.sort(Comparator.comparingInt((FallbackTarget t) -> t.exceptionType() == null ? Integer.MIN_VALUE
                : depth(exceptionTypes.get(t))).reversed());
        return targets;
    }

    /**
     * Maps the candidate's method type variables to the method's, by position, if both declare the same number
     * with the same bounds after renaming. Returns null if they do not; an empty map for two non-generic methods.
     */
    private Map<Element, TypeMirror> typeParameterRenaming(ExecutableType method, ExecutableType candidate) {
        List<? extends TypeVariable> expected = method.getTypeVariables();
        List<? extends TypeVariable> actual = candidate.getTypeVariables();
        if (expected.size() != actual.size()) {
            return null;
        }
        Map<Element, TypeMirror> renaming = new LinkedHashMap<>();
        for (int i = 0; i < actual.size(); i++) {
            renaming.put(actual.get(i).asElement(), expected.get(i));
        }
        for (int i = 0; i < actual.size(); i++) {
            List<? extends TypeMirror> expectedBounds = upperBounds(expected.get(i));
            List<? extends TypeMirror> actualBounds = upperBounds(actual.get(i));
            if (expectedBounds.size() != actualBounds.size()) {
                return null;
            }
            for (int b = 0; b < actualBounds.size(); b++) {
                if (!types.isSameType(substitute(actualBounds.get(b), renaming), expectedBounds.get(b))) {
                    return null;
                }
            }
        }
        return renaming;
    }

    private static List<? extends TypeMirror> upperBounds(TypeVariable variable) {
        TypeMirror bound = variable.getUpperBound();
        return bound.getKind() == TypeKind.INTERSECTION ? ((IntersectionType) bound).getBounds() : List.of(bound);
    }

    /** {@code type} with the type variables in {@code renaming} replaced. Returns {@code type} itself if there are none. */
    private TypeMirror substitute(TypeMirror type, Map<Element, TypeMirror> renaming) {
        if (renaming.isEmpty()) {
            return type;
        }
        return switch (type.getKind()) {
            case TYPEVAR -> renaming.getOrDefault(((TypeVariable) type).asElement(), type);
            case ARRAY -> types.getArrayType(substitute(((javax.lang.model.type.ArrayType) type).getComponentType(), renaming));
            case WILDCARD -> {
                javax.lang.model.type.WildcardType wildcard = (javax.lang.model.type.WildcardType) type;
                yield types.getWildcardType(
                        wildcard.getExtendsBound() == null ? null : substitute(wildcard.getExtendsBound(), renaming),
                        wildcard.getSuperBound() == null ? null : substitute(wildcard.getSuperBound(), renaming));
            }
            case DECLARED -> {
                DeclaredType declared = (DeclaredType) type;
                TypeElement element = (TypeElement) declared.asElement();
                TypeMirror[] arguments = declared.getTypeArguments().stream().map(a -> substitute(a, renaming))
                        .toArray(TypeMirror[]::new);
                TypeMirror enclosing = declared.getEnclosingType();
                if (enclosing.getKind() == TypeKind.DECLARED
                        && !((DeclaredType) enclosing).getTypeArguments().isEmpty()) {
                    yield types.getDeclaredType((DeclaredType) substitute(enclosing, renaming), element, arguments);
                }
                yield arguments.length == 0 ? type : types.getDeclaredType(element, arguments);
            }
            default -> type;
        };
    }

    private int depth(TypeMirror type) {
        int depth = 0;
        for (List<? extends TypeMirror> supertypes = types.directSupertypes(type); !supertypes.isEmpty();
                supertypes = types.directSupertypes(supertypes.get(0))) {
            depth++;
        }
        return depth;
    }

    private boolean isChecked(TypeMirror thrown) {
        return !types.isAssignable(thrown, elements.getTypeElement("java.lang.RuntimeException").asType())
                && !types.isAssignable(thrown, elements.getTypeElement("java.lang.Error").asType());
    }

    private FallbackModel invalidModel(Element element, ProcessorError error, String message) {
        error(element, error, message);
        return null;
    }

    private Handler invalidHandler(Element element, ProcessorError error, String message) {
        error(element, error, message);
        return null;
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
        boolean subclass = iface.getKind() == ElementKind.CLASS;
        delegateReceiver = subclass ? "super" : "this.delegate";
        if (subclass) {
            out.append("@SuppressWarnings({\"deprecation\", \"removal\", \"serial\"})\n");
            out.append("public final class ").append(simpleName).append(typeParameters(iface.getTypeParameters()))
                    .append(" extends ").append(ifaceType).append(" {\n\n");
        } else {
            out.append("@SuppressWarnings({\"deprecation\", \"removal\"})\n");
            out.append("public final class ").append(simpleName).append(typeParameters(iface.getTypeParameters()))
                    .append(" implements ").append(ifaceType).append(" {\n\n");
            out.append("    private final ").append(ifaceType).append(" delegate;\n");
        }
        for (int i = 0; i < methods.size(); i++) {
            FallbackModel fallback = methods.get(i).fallback();
            if (fallback != null) {
                out.append("    private final ").append(Kind.FALLBACK.policyType).append(" fallback").append(i).append(";\n");
                if (fallback.handler() != null) {
                    out.append("    private final ").append(fallback.handler()).append(" fallbackHandler").append(i).append(";\n");
                }
            }
            for (Kind kind : methods.get(i).policies().keySet()) {
                out.append("    private final ").append(kind.policyType).append(' ').append(kind.field).append(i).append(";\n");
                if (ownsClassifier(methods.get(i), kind, i)) {
                    out.append("    private final ").append(methods.get(i).policies().get(kind).classifier()).append(' ')
                            .append(classifierField(methods.get(i), kind, i)).append(";\n");
                }
            }
        }
        List<SubclassWriter.Constructor> constructors = subclass ? subclasses.constructors(iface) : List.of();
        if (subclass) {
            appendConstructorStart(out, simpleName, constructors.get(0));
        } else {
            out.append("\n    public ").append(simpleName).append("(").append(ifaceType).append(" delegate, ")
                    .append(REGISTRY_TYPE).append(" registry) {\n");
            out.append("        this.delegate = java.util.Objects.requireNonNull(delegate, \"delegate\");\n");
        }
        int initStart = out.length();
        out.append("        java.util.Objects.requireNonNull(registry, \"registry\");\n");
        for (int i = 0; i < methods.size(); i++) {
            FallbackModel fallback = methods.get(i).fallback();
            if (fallback != null) {
                out.append("        this.fallback").append(i).append(" = registry.fallbackPolicy(")
                        .append(literal(fallback.instanceId())).append(", java.util.List.of(").append(fallback.applyOn())
                        .append("), java.util.List.of(").append(fallback.skipOn()).append("));\n");
                if (fallback.handler() != null) {
                    out.append("        this.fallbackHandler").append(i).append(" = new ").append(fallback.handler()).append("();\n");
                }
            }
            for (Map.Entry<Kind, PolicyModel> entry : methods.get(i).policies().entrySet()) {
                Kind kind = entry.getKey();
                PolicyModel policy = entry.getValue();
                out.append("        this.").append(kind.field).append(i).append(" = registry.").append(kind.registryMethod)
                        .append('(').append(literal(policy.instanceId())).append(", ").append(literal(policy.name()))
                        .append(", ").append(literal(policy.config())).append(", ").append(overrides(policy.overrides()))
                        .append(");\n");
                if (ownsClassifier(methods.get(i), kind, i)) {
                    out.append("        this.").append(classifierField(methods.get(i), kind, i)).append(" = new ")
                            .append(policy.classifier()).append("();\n");
                }
            }
        }
        out.append("    }\n");
        String init = out.substring(initStart);
        for (int c = 1; c < constructors.size(); c++) { // the subclass mirrors every constructor of the class
            appendConstructorStart(out, simpleName, constructors.get(c));
            out.append(init);
        }
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
            error(iface, ProcessorError.WRITE_FAILED, "Could not write " + qualifiedName + ": " + e.getMessage()
                    + ". Check that the generated-sources directory is writable and that no other processor or source"
                    + " file defines " + qualifiedName + ".");
        }
    }

    /** Opens a constructor of a generated subclass: the registry, then the class constructor's parameters. */
    private static void appendConstructorStart(StringBuilder out, String simpleName, SubclassWriter.Constructor constructor) {
        out.append("\n    public ").append(constructor.typeVariables()).append(simpleName).append("(").append(REGISTRY_TYPE)
                .append(" registry").append(constructor.parameters()).append(")").append(constructor.throwsClause())
                .append(" {\n");
        out.append("        super(").append(constructor.arguments()).append(");\n");
    }

    private static boolean isObject(Element type) {
        return ((TypeElement) type).getQualifiedName().contentEquals("java.lang.Object");
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
        String delegateCall = delegateReceiver + "." + method.getSimpleName() + "(" + args + ")";

        if (model.asyncResult() != null && (!model.policies().isEmpty() || model.fallback() != null)) {
            writeAsyncMethod(out, model, index, typeVariables, returnType, method.getSimpleName() + signatureTail,
                    String.join(", ", parameters), arguments, delegateCall);
            return;
        }
        // One layer per policy, outermost first. Layer i calls layer i + 1; the last one calls the delegate.
        List<Kind> layers = new ArrayList<>(model.policies().keySet());
        if (model.fallback() != null && !layers.contains(Kind.RETRY)) {
            layers.add(0, Kind.FALLBACK); // with a retry, the fallback is part of the retry layer instead
        }
        List<String> layerNames = new ArrayList<>();
        for (Kind kind : layers) {
            layerNames.add("r4c$" + kind.field + index);
        }
        out.append("\n    @Override\n    public ").append(typeVariables).append(returnType).append(' ')
                .append(method.getSimpleName()).append(signatureTail).append(" {\n");
        if (layers.isEmpty()) {
            appendLines(out, List.of(isVoid ? delegateCall + ";" : "return " + delegateCall + ";"));
        } else {
            appendLines(out, layer(layers.get(0), model, index, inner(layerNames, 1, args, delegateCall), isVoid, returnType, arguments));
        }
        out.append("    }\n");
        for (int i = 1; i < layers.size(); i++) {
            out.append("\n    private ").append(typeVariables).append(returnType).append(' ').append(layerNames.get(i))
                    .append(signatureTail).append(" {\n");
            appendLines(out, layer(layers.get(i), model, index, inner(layerNames, i + 1, args, delegateCall), isVoid, returnType, arguments));
            out.append("    }\n");
        }
    }

    /**
     * An asynchronous method is one expression: each policy's {@code executeAsync} gets the next layer
     * as a lambda, innermost first. A fallback becomes a private {@code r4c$recover<index>} method that
     * returns a stage; with a retry, it is passed to the retry so that it runs when the retries give up.
     * For a method that returns a stage of {@code ? extends X} (or {@code ?}), the policies work with
     * {@code X}: the delegate's stage is widened with {@code AsyncCall.widen}.
     */
    private static void writeAsyncMethod(StringBuilder out, MethodModel model, int index, String typeVariables,
            String returnType, String signature, String parameters, List<String> arguments, String delegateCall) {
        FallbackModel fallback = model.fallback();
        String args = String.join(", ", arguments);
        String result = TypePrinter.print(model.asyncResult());
        String recoverTypeArguments = model.type().getTypeVariables().isEmpty() ? ""
                : "<" + TypePrinter.join(model.type().getTypeVariables(), ", ") + ">";
        String recovery = "(r4c$e, r4c$attempts) -> this." + recoverTypeArguments + "r4c$recover" + index
                + "(r4c$e, r4c$attempts" + (args.isEmpty() ? "" : ", " + args) + ")";
        List<Kind> layers = new ArrayList<>(model.policies().keySet());
        String expression = isWildcardStage(model.type()) ? widen(delegateCall, result) : delegateCall;
        for (int i = layers.size() - 1; i >= 0; i--) {
            Kind kind = layers.get(i);
            String policy = "this." + kind.field + index;
            String classifier = classifierField(model, kind, index);
            if (classifier != null && kind == Kind.RETRY) {
                expression = policy + ".executeAsync(() -> " + expression + ", this." + classifier + ", "
                        + (model.policies().get(kind).throwOnExhaustedResult() ? "true, " : "")
                        + (fallback != null ? "this.fallback" + index + ", " + recovery : "null, null") + ")";
            } else if (classifier != null) {
                expression = policy + ".executeAsync(() -> " + expression + ", this." + classifier + ")";
            } else if (kind == Kind.RETRY && fallback != null) {
                expression = policy + ".executeAsync(() -> " + expression + ", this.fallback" + index + ", " + recovery + ")";
            } else {
                expression = policy + ".executeAsync(() -> " + expression + ")";
            }
        }
        if (fallback != null && !layers.contains(Kind.RETRY)) {
            expression = "this.fallback" + index + ".executeAsync(() -> " + expression + ", " + recovery + ")";
        }
        out.append("\n    @Override\n    public ").append(typeVariables).append(returnType).append(' ').append(signature)
                .append(" {\n");
        appendLines(out, List.of("return " + expression + ";"));
        out.append("    }\n");
        if (fallback == null) {
            return;
        }
        String stage = "java.util.concurrent.CompletionStage<" + result + ">";
        out.append("\n    private ").append(typeVariables).append(stage).append(" r4c$recover").append(index)
                .append("(java.lang.Throwable r4c$e, int r4c$attempts").append(parameters.isEmpty() ? "" : ", " + parameters)
                .append(") throws java.lang.Throwable {\n");
        appendLines(out, asyncFallbackLines(fallback, index, arguments, result));
        out.append("    }\n");
    }

    /**
     * The body of an async fallback: returns a stage of {@code result}, or throws to fail the call.
     * {@code handles} was already checked.
     */
    private static List<String> asyncFallbackLines(FallbackModel fallback, int index, List<String> arguments,
            String result) {
        List<String> lines = new ArrayList<>();
        if (fallback.handler() != null) {
            lines.add("final dev.resilience4cata.FallbackContext r4c$context = this.fallback" + index
                    + ".context(r4c$e, r4c$attempts, new java.lang.Object[] {" + String.join(", ", arguments) + "});");
            String apply = "this.fallbackHandler" + index + ".apply(r4c$context)";
            lines.add("return " + (fallback.handlerWidens() ? widen(apply, result)
                    : asStage(apply, fallback.handlerReturnsValue())) + ";");
            return lines;
        }
        for (int k = 0; k < fallback.targets().size(); k++) {
            FallbackTarget target = fallback.targets().get(k);
            List<String> callArguments = new ArrayList<>(arguments);
            if (target.exceptionType() == null || target.catchesAll()) {
                if (target.exceptionType() != null) {
                    callArguments.add("r4c$e");
                }
                lines.add("return " + asStage(target, target.receiver() + "." + target.name() + "("
                        + String.join(", ", callArguments) + ")", result) + ";");
                return lines;
            }
            callArguments.add("r4c$f" + k);
            lines.add("if (r4c$e instanceof " + target.exceptionType() + " r4c$f" + k + ") {");
            lines.add("    return " + asStage(target, target.receiver() + "." + target.name() + "("
                    + String.join(", ", callArguments) + ")", result) + ";");
            lines.add("}");
        }
        lines.add("throw r4c$e;");
        return lines;
    }

    private static String asStage(String call, boolean returnsValue) {
        return returnsValue ? "java.util.concurrent.CompletableFuture.completedFuture(" + call + ")" : call;
    }

    private static String asStage(FallbackTarget target, String call, String result) {
        return target.widens() ? widen(call, result) : asStage(call, target.returnsValue());
    }

    /** Views a {@code CompletionStage<? extends result>} as a {@code CompletionStage<result>}. */
    private static String widen(String stage, String result) {
        return "dev.resilience4cata.generated.AsyncCall.<" + result + ">widen(" + stage + ")";
    }

    private static String inner(List<String> layerNames, int next, String args, String delegateCall) {
        return next < layerNames.size() ? "this." + layerNames.get(next) + "(" + args + ")" : delegateCall;
    }

    private static void appendLines(StringBuilder out, List<String> lines) {
        for (String line : lines) {
            out.append(line.isEmpty() ? "" : "        " + line).append('\n');
        }
    }

    private static List<String> layer(Kind kind, MethodModel model, int index, String call, boolean isVoid,
            String returnType, List<String> arguments) {
        return switch (kind) {
            case FALLBACK -> fallback(model.fallback(), index, call, isVoid, arguments);
            case RETRY -> classifierField(model, kind, index) == null
                    ? retry(index, call, isVoid, giveUp(model, index, "r4c$e", "r4c$attempts", isVoid, arguments))
                    : classifiedRetry(index, call, isVoid, returnType, model.policies().get(kind).classifier(),
                            classifierField(model, kind, index),
                            giveUp(model, index, "r4c$e", "r4c$attempts", isVoid, arguments),
                            model.fallback() == null ? null
                                    : giveUp(model, index, "r4c$c", "r4c$attempts + 1", isVoid, arguments),
                            !model.policies().get(kind).throwOnExhaustedResult() ? null
                                    : model.fallback() == null ? List.of()
                                    : giveUp(model, index, "r4c$r", "r4c$attempts", isVoid, arguments));
            case CIRCUIT_BREAKER -> classifierField(model, kind, index) == null
                    ? circuitBreaker(index, call, isVoid, returnType)
                    : classifiedCircuitBreaker(index, call, isVoid, returnType, model.policies().get(kind).classifier(),
                            classifierField(model, kind, index));
            case RATE_LIMITER -> rateLimiter(index, call, isVoid);
            case TIMEOUT -> timeout(index, call, isVoid, returnType);
            case BULKHEAD -> bulkhead(index, call, isVoid);
        };
    }

    private static List<String> retry(int index, String call, boolean isVoid, List<String> giveUp) {
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
        for (String line : giveUp) {
            lines.add("            " + line);
        }
        lines.add("        }");
        lines.add("    }");
        lines.add("}");
        return lines;
    }

    /** What the retry layer does when it gives up on the failure in {@code failure}: run the fallback, or rethrow. */
    private static List<String> giveUp(MethodModel model, int index, String failure, String attempts, boolean isVoid,
            List<String> arguments) {
        return model.fallback() == null ? List.of("throw " + failure + ";")
                : fallbackLines(model.fallback(), index, failure, attempts, isVoid, arguments);
    }

    /**
     * The field that holds the classifier of {@code kind}'s layer, or null if it has none. When the retry and
     * the breaker use the same classifier class, the breaker shares the retry's instance.
     */
    private static String classifierField(MethodModel model, Kind kind, int index) {
        PolicyModel policy = model.policies().get(kind);
        if (policy == null || policy.classifier() == null) {
            return null;
        }
        PolicyModel retry = model.policies().get(Kind.RETRY);
        if (kind == Kind.CIRCUIT_BREAKER && retry != null && policy.classifier().equals(retry.classifier())) {
            return Kind.RETRY.field + "Classifier" + index;
        }
        return kind.field + "Classifier" + index;
    }

    /** True if {@code kind}'s layer has a classifier field of its own (one that it does not share). */
    private static boolean ownsClassifier(MethodModel model, Kind kind, int index) {
        String field = classifierField(model, kind, index);
        return field != null && field.equals(kind.field + "Classifier" + index);
    }

    private static final String OUTCOME = "dev.resilience4cata.Classifier.Outcome";

    /**
     * A retry loop with a classifier. Exceptions get the classifier's verdict (abortOn still wins); a result
     * classified FAILURE is retried, and returned when the retries give up. If the classifier throws, the
     * loop publishes the give-up and gives up with that exception ({@code giveUpOnClassifier}, which reads it
     * from {@code r4c$c}); null when there is no fallback, so that the exception simply propagates.
     * {@code giveUpOnRejected} is null for {@code onExhaustedResult = RETURN}; for {@code THROW}, the fallback
     * statements that read the {@code ResultRejectedException} from {@code r4c$r}, or empty without a fallback.
     */
    private static List<String> classifiedRetry(int index, String call, boolean isVoid, String returnType,
            String classifierType, String classifierField, List<String> giveUp, List<String> giveUpOnClassifier,
            List<String> giveUpOnRejected) {
        List<String> onClassifierFailure = new ArrayList<>();
        onClassifierFailure.add("r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);");
        onClassifierFailure.addAll(giveUpOnClassifier == null ? List.of("throw r4c$c;") : giveUpOnClassifier);
        List<String> lines = new ArrayList<>();
        lines.add("final " + Kind.RETRY.policyType + " r4c$retry = this." + Kind.RETRY.field + index + ";");
        lines.add("final " + classifierType + " r4c$classifier = this." + classifierField + ";");
        lines.add("final long r4c$start = r4c$retry.start();");
        lines.add("int r4c$attempts = 0;");
        lines.add("while (true) {");
        if (!isVoid) {
            lines.add("    final " + returnType + " r4c$result;");
        }
        lines.add("    try {");
        lines.add("        " + (isVoid ? "" : "r4c$result = ") + call + ";");
        lines.add("    } catch (java.lang.Throwable r4c$e) {");
        lines.addAll(indent(indent(classify("null, r4c$e", onClassifierFailure))));
        lines.add("        final long r4c$delay = r4c$retry.onFailure(++r4c$attempts, r4c$e, r4c$start, r4c$verdict);");
        lines.add("        if (r4c$delay < 0 || !r4c$retry.pause(r4c$delay)) {");
        for (String line : giveUp) {
            lines.add("            " + line);
        }
        lines.add("        }");
        lines.add("        continue;");
        lines.add("    }");
        lines.addAll(indent(classify((isVoid ? "null" : "r4c$result") + ", null", onClassifierFailure)));
        String accept = isVoid ? "return;" : "return r4c$result;";
        lines.add("    if (r4c$verdict != " + OUTCOME + ".FAILURE) {");
        lines.add("        " + accept);
        lines.add("    }");
        lines.add("    final long r4c$wait = r4c$retry.onFailedResult(++r4c$attempts, r4c$start);");
        lines.add("    if (r4c$wait < 0 || !r4c$retry.pause(r4c$wait)) {");
        String rejected = "r4c$retry.rejected(" + (isVoid ? "null" : "r4c$result") + ", r4c$attempts)";
        if (giveUpOnRejected == null) {
            lines.add("        " + accept + " // rejected, but no retries left: the last result");
        } else if (giveUpOnRejected.isEmpty()) {
            lines.add("        throw " + rejected + "; // rejected, and no retries left");
        } else {
            // A catch parameter, so that the fallback's "throw r4c$r" is a precise rethrow.
            lines.add("        try {");
            lines.add("            throw " + rejected + ";");
            lines.add("        } catch (java.lang.Throwable r4c$r) {");
            for (String line : giveUpOnRejected) {
                lines.add("            " + line);
            }
            lines.add("        }");
        }
        lines.add("    }");
        lines.add("}");
        return lines;
    }

    /** Declares {@code r4c$verdict}; if {@code onThrow} is not null, it handles an exception from the classifier in {@code r4c$c}. */
    private static List<String> classify(String arguments, List<String> onThrow) {
        List<String> lines = new ArrayList<>();
        if (onThrow == null) {
            lines.add("final " + OUTCOME + " r4c$verdict = r4c$classifier.classify(" + arguments + ");");
            return lines;
        }
        lines.add("final " + OUTCOME + " r4c$verdict;");
        lines.add("try {");
        lines.add("    r4c$verdict = r4c$classifier.classify(" + arguments + ");");
        lines.add("} catch (java.lang.Throwable r4c$c) {");
        lines.addAll(indent(onThrow));
        lines.add("}");
        return lines;
    }

    /**
     * A breaker layer with a classifier: the call's result or exception is recorded as the classifier says
     * (ignoreOn still wins for exceptions). If the classifier throws, the call is recorded as a failure, so
     * that a half-open probe permit is never lost, and the classifier's exception propagates.
     */
    private static List<String> classifiedCircuitBreaker(int index, String call, boolean isVoid, String returnType,
            String classifierType, String classifierField) {
        List<String> lines = new ArrayList<>();
        lines.add("final " + Kind.CIRCUIT_BREAKER.policyType + " r4c$cb = this." + Kind.CIRCUIT_BREAKER.field + index + ";");
        lines.add("final " + classifierType + " r4c$classifier = this." + classifierField + ";");
        lines.add("final long r4c$token = r4c$cb.acquire();");
        lines.add("if (r4c$token < 0) {");
        lines.add("    throw r4c$cb.rejection();");
        lines.add("}");
        lines.add("final long r4c$started = r4c$cb.startTimer();");
        if (!isVoid) {
            lines.add("final " + returnType + " r4c$result;");
        }
        lines.add("try {");
        lines.add("    " + (isVoid ? "" : "r4c$result = ") + call + ";");
        lines.add("} catch (java.lang.Throwable r4c$e) {");
        List<String> recordFailure = List.of("r4c$cb.onResult(r4c$token, r4c$started, " + OUTCOME + ".FAILURE);",
                "throw r4c$c;");
        lines.addAll(indent(classify("null, r4c$e", recordFailure)));
        lines.add("    r4c$cb.onFailure(r4c$token, r4c$started, r4c$e, r4c$verdict);");
        lines.add("    throw r4c$e;");
        lines.add("}");
        lines.addAll(classify((isVoid ? "null" : "r4c$result") + ", null", recordFailure));
        lines.add("r4c$cb.onResult(r4c$token, r4c$started, r4c$verdict);");
        lines.add(isVoid ? "return;" : "return r4c$result;");
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

    private static List<String> fallback(FallbackModel fallback, int index, String call, boolean isVoid,
            List<String> arguments) {
        List<String> lines = new ArrayList<>();
        lines.add("try {");
        if (isVoid) {
            lines.add("    " + call + ";");
            lines.add("    return;");
        } else {
            lines.add("    return " + call + ";");
        }
        lines.add("} catch (java.lang.Throwable r4c$e) {");
        for (String line : fallbackLines(fallback, index, "r4c$e", "1", isVoid, arguments)) {
            lines.add("    " + line);
        }
        lines.add("}");
        return lines;
    }

    /**
     * Statements that run the fallback for the failure in {@code failure}, a catch parameter (normally
     * {@code r4c$e}; {@code r4c$c} for an exception thrown by a classifier). They always
     * end the method: with the fallback's result, its exception, or the original failure rethrown.
     */
    private static List<String> fallbackLines(FallbackModel fallback, int index, String failure, String attempts, boolean isVoid,
            List<String> arguments) {
        List<String> lines = new ArrayList<>();
        lines.add("if (!this.fallback" + index + ".handles(" + failure + ")) {");
        lines.add("    throw " + failure + ";");
        lines.add("}");
        if (fallback.handler() != null) {
            lines.add("final dev.resilience4cata.FallbackContext r4c$context = this.fallback" + index + ".context(" + failure + ", "
                    + attempts + ", new java.lang.Object[] {" + String.join(", ", arguments) + "});");
            lines.add("try {");
            String apply = "this.fallbackHandler" + index + ".apply(r4c$context)";
            lines.addAll(indent(returning(apply, isVoid)));
            lines.add("} catch (java.lang.Throwable r4c$x) {");
            for (int k = 0; k < fallback.checkedThrown().size(); k++) {
                lines.add("    if (r4c$x instanceof " + fallback.checkedThrown().get(k) + " r4c$d" + k + ") {");
                lines.add("        throw r4c$d" + k + ";");
                lines.add("    }");
            }
            lines.add("    throw " + Kind.FALLBACK.policyType + ".unchecked(r4c$x);");
            lines.add("}");
            return lines;
        }
        for (int k = 0; k < fallback.targets().size(); k++) {
            FallbackTarget target = fallback.targets().get(k);
            List<String> callArguments = new ArrayList<>(arguments);
            if (target.exceptionType() == null || target.catchesAll()) {
                if (target.exceptionType() != null) {
                    callArguments.add(failure);
                }
                lines.addAll(returning(target.receiver() + "." + target.name() + "(" + String.join(", ", callArguments) + ")", isVoid));
                return lines; // catches everything: nothing after it would be reachable
            }
            callArguments.add("r4c$f" + k);
            lines.add("if (" + failure + " instanceof " + target.exceptionType() + " r4c$f" + k + ") {");
            lines.addAll(indent(returning(target.receiver() + "." + target.name() + "(" + String.join(", ", callArguments) + ")", isVoid)));
            lines.add("}");
        }
        lines.add("throw " + failure + ";");
        return lines;
    }

    private static List<String> returning(String call, boolean isVoid) {
        return isVoid ? List.of(call + ";", "return;") : List.of("return " + call + ";");
    }

    private static List<String> indent(List<String> lines) {
        return lines.stream().map(line -> "    " + line).toList();
    }

    private static List<String> rateLimiter(int index, String call, boolean isVoid) {
        List<String> lines = new ArrayList<>();
        lines.add("final " + Kind.RATE_LIMITER.policyType + " r4c$rateLimiter = this." + Kind.RATE_LIMITER.field + index + ";");
        lines.add("if (!r4c$rateLimiter.tryAcquire()) {");
        lines.add("    throw r4c$rateLimiter.rejection();");
        lines.add("}");
        if (isVoid) {
            lines.add(call + ";");
            lines.add("return;");
        } else {
            lines.add("return " + call + ";");
        }
        return lines;
    }

    private static List<String> bulkhead(int index, String call, boolean isVoid) {
        List<String> lines = new ArrayList<>();
        lines.add("final " + Kind.BULKHEAD.policyType + " r4c$bulkhead = this." + Kind.BULKHEAD.field + index + ";");
        lines.add("if (!r4c$bulkhead.tryEnter()) {");
        lines.add("    throw r4c$bulkhead.rejection();");
        lines.add("}");
        lines.add("try {");
        lines.add("    " + (isVoid ? "" : "return ") + call + ";");
        lines.add("} finally {");
        lines.add("    r4c$bulkhead.exit();");
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

    private static String methodTypeVariables(List<? extends TypeVariable> variables, String none) {
        return variables.isEmpty() ? none : methodTypeVariables(variables) + " ";
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

    private boolean invalid(Element element, ProcessorError error, String message) {
        error(element, error, message);
        return false;
    }

    /** Reports {@code error} on {@code element}; {@code message} says where, what and how to fix it. */
    private void error(Element element, ProcessorError error, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, error.message(message), element);
    }

    /**
     * {@code fallback} is null when the method has no {@code @Fallback}; {@code asyncResult} is the
     * {@code T} of a method that returns a stage, null for a synchronous method.
     */
    private record MethodModel(ExecutableElement method, ExecutableType type, Map<Kind, PolicyModel> policies,
            FallbackModel fallback, TypeMirror asyncResult) {
    }

    /**
     * A validated {@code @Fallback}. Exactly one of {@code handler} (qualified class name) and
     * {@code targets} (sibling methods, most specific exception first) is set; the other is null or empty.
     * {@code checkedThrown} lists the checked exceptions the method declares, which a handler may throw as is.
     * {@code handlerWidens}: for an async method, the handler returns a {@code CompletionStage<? extends T>}.
     */
    private record FallbackModel(String instanceId, String applyOn, String skipOn, String handler,
            boolean handlerReturnsValue, List<FallbackTarget> targets, List<String> checkedThrown,
            boolean handlerWidens) {
    }

    /**
     * One sibling fallback method. {@code name} is what follows the receiver in the call: the method name,
     * preceded by explicit type arguments for a generic method ({@code <T>load}). {@code exceptionType} is
     * null when it takes no exception parameter; {@code catchesAll} is true when it takes none or takes
     * {@code Throwable}. {@code returnsValue}: for an async method, it returns {@code T} rather than a stage;
     * {@code widens}: it returns a {@code CompletionStage<? extends T>}.
     */
    private record FallbackTarget(String receiver, String name, String exceptionType, boolean catchesAll,
            boolean returnsValue, boolean widens) {
    }

    /**
     * {@code classifier} is the qualified name of the classifier class, or null for none.
     * {@code throwOnExhaustedResult}: {@code @Retry(onExhaustedResult = THROW)}.
     */
    private record PolicyModel(String instanceId, String name, String config, List<String> overrides,
            String classifier, boolean throwOnExhaustedResult) {

        PolicyModel(String instanceId, String name, String config, List<String> overrides, String classifier) {
            this(instanceId, name, config, overrides, classifier, false);
        }
    }
}
