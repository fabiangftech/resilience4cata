package dev.resilience4cata.processor;

import dev.resilience4cata.annotation.AdaptiveLimit;
import dev.resilience4cata.annotation.Bulkhead;
import dev.resilience4cata.annotation.CircuitBreaker;
import dev.resilience4cata.annotation.Fallback;
import dev.resilience4cata.annotation.Hedge;
import dev.resilience4cata.annotation.PolicyOrder;
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
import javax.tools.StandardLocation;

/**
 * Generates, for every interface that uses resilience annotations, a class named
 * {@code <Interface>$Resilient} that implements the interface, delegates every method, and applies
 * the declared policies. {@code Resilient.of(...)} in the core module finds it by that name.
 *
 * <p>Policies are nested in the default order, outermost first: Fallback, Retry (or Hedge), CircuitBreaker,
 * RateLimiter, AdaptiveLimit, Timeout, Bulkhead. Each attempt is recorded by the breaker, an open breaker stops the
 * retries, calls rejected by the breaker use no rate-limiter permit, the adaptive limit sees the timeouts and the
 * bulkhead's rejections as drops, the timeout
 * applies to each attempt (including the wait for a bulkhead slot), and a bulkhead slot is held only
 * while the method runs. The fallback sees the final outcome, rejections included; when the method also
 * retries, the fallback runs where the retry loop gives up, so that it knows the number of attempts. The public method holds the outermost layer; every inner layer is a private method
 * of the generated class, which the JIT inlines like any statically bound call.
 */
// java:S1192: the remaining duplicates are fragments of the generated source (braces, keywords, indentation,
// the r4c$ local names) and of error messages, which read better in place than as constants.
@SuppressWarnings("java:S1192")
public final class ResilienceProcessor extends AbstractProcessor {

    private static final String SUFFIX = "$Resilient";
    private static final String REGISTRY_TYPE = "dev.resilience4cata.ResilienceRegistry";
    private static final String DELEGATE = "this.delegate";
    private static final String OBJECT = "java.lang.Object";
    private static final String COMPLETION_STAGE = "java.util.concurrent.CompletionStage";
    /** Methods that return it are asynchronous, like stages, when resilience4cata-reactor provides {@link #MONOS}. */
    private static final String MONO = "reactor.core.publisher.Mono";
    /** What generated {@code Mono} methods call: present only with resilience4cata-reactor on the class path. */
    private static final String MONOS = "dev.resilience4cata.reactor.generated.Monos";
    /** Methods that return it are asynchronous streams, when resilience4cata-reactor provides {@link #FLUXES}. */
    private static final String FLUX = "reactor.core.publisher.Flux";
    /** What generated {@code Flux} methods call: resilience4cata-reactor 0.11.0 or newer. */
    private static final String FLUXES = "dev.resilience4cata.reactor.generated.Fluxes";
    /**
     * Lazy reactive types: the method only builds a pipeline that runs when subscribed, so policies around the call
     * would protect the building, not the work. Checked when present on the classpath.
     */
    private static final List<String> REACTIVE_TYPES = List.of("java.util.concurrent.Flow.Publisher",
            "org.reactivestreams.Publisher", "io.smallrye.mutiny.Uni", "io.smallrye.mutiny.Multi",
            "io.reactivex.rxjava3.core.Single", "io.reactivex.rxjava3.core.Maybe", "io.reactivex.rxjava3.core.Completable",
            "io.reactivex.rxjava3.core.Observable");
    private static final String EXECUTE_ASYNC = ".executeAsync(() -> ";
    private static final String POLICIES = "dev.resilience4cata.generated.Policies";
    /**
     * The generated-code level of this processor, which every generated constructor passes to core's
     * {@code Abi.require} (and the reactor module's {@code ReactorAbi.require}). Raise it, together with the
     * runtimes' own level, when the generated code starts calling something older runtimes do not have.
     */
    private static final int GENERATED_CODE_LEVEL = 2;

    /** Supported policies, in nesting order: outermost first. */
    private enum Kind {
        FALLBACK(Fallback.class, "fallback", "dev.resilience4cata.generated.FallbackPolicy", "", ""),
        RETRY(Retry.class, "retry", "dev.resilience4cata.generated.RetryPolicy", "retry",
                "dev.resilience4cata.RetryConfig"),
        /** Takes Retry's place (the two are exclusive on one method); asynchronous methods only. */
        HEDGE(Hedge.class, "hedge", "dev.resilience4cata.generated.HedgePolicy", "hedge",
                "dev.resilience4cata.HedgeConfig"),
        CIRCUIT_BREAKER(CircuitBreaker.class, "circuitBreaker", "dev.resilience4cata.generated.CircuitBreakerPolicy",
                "circuitBreaker", "dev.resilience4cata.CircuitBreakerConfig"),
        RATE_LIMITER(RateLimiter.class, "rateLimiter", "dev.resilience4cata.generated.RateLimiterPolicy",
                "rateLimiter", "dev.resilience4cata.RateLimiterConfig"),
        ADAPTIVE_LIMIT(AdaptiveLimit.class, "adaptiveLimit", "dev.resilience4cata.generated.AdaptiveLimitPolicy",
                "adaptiveLimit", "dev.resilience4cata.AdaptiveLimitConfig"),
        TIMEOUT(Timeout.class, "timeout", "dev.resilience4cata.generated.TimeoutPolicy", "timeout",
                "dev.resilience4cata.TimeoutConfig"),
        BULKHEAD(Bulkhead.class, "bulkhead", "dev.resilience4cata.generated.BulkheadPolicy", "bulkhead",
                "dev.resilience4cata.BulkheadConfig");

        final Class<?> annotation;
        final String field;
        final String policyType;
        /** The {@code dev.resilience4cata.generated.Policies} method that creates it. */
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
    /** {@code @PolicyOrder}, read with the same precedence and bundle rules as the policy annotations. */
    private PolicyAttributes order;
    private final Set<String> processed = new HashSet<>();
    private SubclassWriter subclasses;
    /** {@code -Ar4c.inject=micronaut}: generated subclasses become Micronaut beans that replace their class. */
    private boolean micronautBeans;
    /** The invalid value of {@link #INJECT_OPTION}, until it is reported; else null. */
    private String invalidInjectOption;
    /** What generated methods call: the wrapped instance for an interface, {@code super} for a class. */
    private String delegateReceiver = DELEGATE;

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
        order = new PolicyAttributes(messager, PolicyOrder.class);
        String inject = processingEnv.getOptions().get(INJECT_OPTION);
        if (inject != null && !inject.isBlank()) {
            micronautBeans = inject.strip().equals(INJECT_MICRONAUT);
            invalidInjectOption = micronautBeans ? null : inject;
        }
    }

    /** The option that makes generated subclasses beans of a dependency injection container: {@value}. */
    static final String INJECT_OPTION = "r4c.inject";
    /** The only supported value of {@link #INJECT_OPTION}: {@value}. */
    static final String INJECT_MICRONAUT = "micronaut";
    private static final String MICRONAUT_REPLACES = "io.micronaut.context.annotation.Replaces";

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(INJECT_OPTION);
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
        if (invalidInjectOption != null) {
            messager.printMessage(Diagnostic.Kind.ERROR, ProcessorError.INVALID_INJECT_OPTION.message("Unknown value '"
                    + invalidInjectOption + "' for the compiler option -A" + INJECT_OPTION + ". The only value is "
                    + INJECT_MICRONAUT + " (generated subclasses of annotated classes become Micronaut beans); remove the"
                    + " option for other containers, or set -A" + INJECT_OPTION + "=" + INJECT_MICRONAUT + "."));
            invalidInjectOption = null; // reported once
        }
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
            case ANNOTATION_TYPE -> checkBundle(type);
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
                    String kinds = type.getKind() == ElementKind.ENUM ? "enums" : "records";
                    error(type, ProcessorError.ENUM_OR_RECORD, "Resilience annotations cannot be used on " + kinds
                            + ": the generated subclass must extend the type, and " + kinds + " are final. Declare the"
                            + " methods in an interface and wrap the implementation with Resilient.of(...).");
                }
            }
        }
    }

    /** An annotation type that uses a policy annotation must be a {@code @ResiliencePolicy} bundle. */
    private void checkBundle(TypeElement type) {
        for (PolicyAttributes policy : attributes.values()) {
            if (PolicyAttributes.hasAnnotation(type, policy.annotation())
                    && !PolicyAttributes.hasAnnotation(type, PolicyAttributes.POLICY)) {
                error(type, ProcessorError.BUNDLE_NOT_MARKED, "@" + type.getSimpleName() + " uses @" + policy.simpleName()
                        + " but is not marked with @ResiliencePolicy, so its policies would be ignored."
                        + " Add @ResiliencePolicy to it.");
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

    @SuppressWarnings("java:S3776") // java:S3776: one pass over the methods that collects every error first
    private void generateIfNeeded(TypeElement iface) {
        String binaryName = elements.getBinaryName(iface).toString();
        if (!processed.add(binaryName)) {
            return;
        }
        boolean valid = checkDeclaredMethods(iface);

        Map<Kind, Optional<Map<String, AnnotationValue>>> typeLevel = new EnumMap<>(Kind.class);
        attributes.forEach((kind, policy) -> typeLevel.put(kind, policy.on(iface)));
        Optional<Map<String, AnnotationValue>> typeOrder = order.on(iface);
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
                } else {
                    PolicyModel model = policyModel(kind, iface, method, type, asyncResult, effective.get());
                    if (model == null) {
                        valid = false;
                    } else {
                        policies.put(kind, model);
                    }
                }
            }
            boolean annotated = !policies.isEmpty() || fallback != null || effective(Kind.FALLBACK, iface,
                    typeLevel.get(Kind.FALLBACK), method).isPresent();
            valid &= checkHedge(iface, method, type, asyncResult, typeLevel);
            if (annotated && asyncResult != null && asyncResult.getKind() == TypeKind.NONE) {
                error(method, ProcessorError.UNSUPPORTED_ASYNC_TYPE, "An asynchronous method must return CompletionStage<T> or CompletableFuture<T>"
                        + " (or Mono<T> or Flux<T>, with resilience4cata-reactor), where T may also be ? or ? extends X (not ? super X),"
                        + " to use resilience annotations; it returns " + TypePrinter.print(type.getReturnType()) + ".");
                valid = false;
            }
            if (annotated && asyncResult != null && (isMono(type) && elements.getTypeElement(MONOS) == null
                    || isFlux(type) && elements.getTypeElement(FLUXES) == null)) {
                String kind = isMono(type) ? "Mono" : "Flux";
                error(method, ProcessorError.REACTOR_MISSING, "The method returns " + TypePrinter.print(type.getReturnType())
                        + ", and " + kind + " methods need resilience4cata-reactor" + (isFlux(type) ? " 0.11.0 or newer" : "")
                        + " on the compile class path, next to resilience4cata-core: add implementation"
                        + " 'dev.resilience4cata:resilience4cata-reactor:<version>' (Gradle) or the dependency"
                        + " dev.resilience4cata:resilience4cata-reactor (Maven).");
                valid = false;
            }
            String reactive = annotated && asyncResult == null ? reactiveType(type.getReturnType()) : null;
            if (reactive != null) {
                error(method, ProcessorError.REACTIVE_TYPE, "The method returns " + TypePrinter.print(type.getReturnType())
                        + ", a " + reactive + ": it only builds a pipeline that runs later, when subscribed, so the"
                        + " policies would protect building it, not the work. Return CompletionStage<T> or"
                        + " CompletableFuture<T> (for example Uni.subscribeAsCompletionStage() in the implementation),"
                        + " or Reactor's Mono<T> or Flux<T> with resilience4cata-reactor, or use the reactive library's"
                        + " own retry and timeout operators.");
                valid = false;
            }
            List<Kind> layers = layers(iface, typeOrder, method, policies);
            valid &= layers != null;
            if (annotated && isFlux(type) && asyncResult != null && asyncResult.getKind() != TypeKind.NONE) {
                valid &= checkFlux(iface, method, typeLevel, policies, layers);
            }
            if (annotated || iface.getKind() == ElementKind.INTERFACE) { // a subclass overrides only protected methods
                methods.add(new MethodModel(method, type, policies, fallback,
                    asyncResult == null || asyncResult.getKind() == TypeKind.NONE ? null : asyncResult,
                    layers == null ? List.copyOf(policies.keySet()) : layers));
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

    /**
     * A {@code Flux} method's policies act on a stream: a hedge would merge two streams, a classifier judges one
     * result while a stream has many, and the rule that the retry and the fallback stop at the first element is
     * defined for the default order. Returns false after reporting an error.
     */
    private boolean checkFlux(TypeElement iface, ExecutableElement method,
            Map<Kind, Optional<Map<String, AnnotationValue>>> typeLevel, Map<Kind, PolicyModel> policies, List<Kind> layers) {
        List<String> problems = new ArrayList<>();
        if (effective(Kind.HEDGE, iface, typeLevel.get(Kind.HEDGE), method).isPresent()) {
            problems.add("@Hedge (it would merge the elements of two streams; use @Retry)");
        }
        for (Map.Entry<Kind, PolicyModel> policy : policies.entrySet()) {
            if (policy.getValue().classifier() != null) {
                problems.add("a classifier on @" + policy.getKey().annotation.getSimpleName()
                        + " (it judges one result, and a stream has many; use retryOn/abortOn or recordOn/ignoreOn)");
            }
        }
        if (layers != null && !layers.equals(List.copyOf(policies.keySet()))) {
            problems.add("@PolicyOrder (the retry and the fallback stop at the stream's first element in the default"
                    + " order only; remove it)");
        }
        if (problems.isEmpty()) {
            return true;
        }
        return invalid(method, ProcessorError.FLUX_UNSUPPORTED, method.getSimpleName() + " returns "
                + TypePrinter.print(method.getReturnType()) + ", and a Flux method cannot use " + String.join(", nor ", problems)
                + ". Remove it, or return a Mono or a CompletionStage where you need it.");
    }

    /**
     * {@code @Hedge} needs an asynchronous method, and cannot be combined with {@code @Retry}. Returns
     * false after reporting an error.
     */
    private boolean checkHedge(TypeElement iface, ExecutableElement method, ExecutableType type, TypeMirror asyncResult,
            Map<Kind, Optional<Map<String, AnnotationValue>>> typeLevel) {
        if (effective(Kind.HEDGE, iface, typeLevel.get(Kind.HEDGE), method).isEmpty()) {
            return true;
        }
        boolean valid = true;
        if (asyncResult == null) {
            String result = TypePrinter.print(boxed(type.getReturnType()));
            valid = invalid(method, ProcessorError.HEDGE_NOT_ASYNC, "@Hedge needs an asynchronous method: a hedge starts"
                    + " the call again while the first attempt is still running. " + method.getSimpleName() + " returns "
                    + TypePrinter.print(type.getReturnType()) + "; return java.util.concurrent.CompletableFuture<" + result
                    + "> (or CompletionStage<" + result + ">) instead, or remove @Hedge from it. A @Hedge on the type (or in a"
                    + " @ResiliencePolicy bundle on the type) applies to every method, synchronous ones included: annotate"
                    + " the async methods instead of the type.");
        }
        if (effective(Kind.RETRY, iface, typeLevel.get(Kind.RETRY), method).isPresent()) {
            valid = invalid(method, ProcessorError.HEDGE_WITH_RETRY, "@Hedge and @Retry cannot be combined on "
                    + method.getSimpleName() + " (directly, on the type, or through a @ResiliencePolicy bundle). Keep one:"
                    + " a hedge already starts a new attempt when one fails (hedgeOn), and a retry around it would"
                    + " multiply the attempts.") && valid;
        }
        return valid;
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
        return effective(attributes.get(kind), iface, typeLevel, method);
    }

    private Optional<Map<String, AnnotationValue>> effective(PolicyAttributes policy, TypeElement iface,
            Optional<Map<String, AnnotationValue>> typeLevel, ExecutableElement method) {
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
     * The method's policy layers, outermost first (the fallback is not one: it is always outermost): the default
     * order, changed by an effective {@code @PolicyOrder}. Null after reporting a layer listed twice.
     */
    private List<Kind> layers(TypeElement iface, Optional<Map<String, AnnotationValue>> typeOrder,
            ExecutableElement method, Map<Kind, PolicyModel> policies) {
        List<Kind> defaults = List.copyOf(policies.keySet()); // EnumMap: the default order; FALLBACK is never there
        AnnotationValue value = effective(order, iface, typeOrder, method).map(v -> v.get("value")).orElse(null);
        if (value == null) {
            return defaults;
        }
        List<Kind> listed = new ArrayList<>();
        for (Object item : (List<?>) value.getValue()) {
            Kind kind = Kind.valueOf(((VariableElement) ((AnnotationValue) item).getValue()).getSimpleName().toString());
            if (listed.contains(kind)) {
                error(method, ProcessorError.ORDER_DUPLICATE, "@PolicyOrder lists " + kind + " twice: "
                        + value + ". List each layer once, outermost first.");
                return null;
            }
            listed.add(kind);
        }
        return reorder(defaults, listed);
    }

    /**
     * Moves the {@code listed} layers that {@code defaults} has together, in the listed order, to the place of the
     * outermost of them; the others keep their default order. With fewer than two of them, nothing moves.
     */
    static List<Kind> reorder(List<Kind> defaults, List<Kind> listed) {
        List<Kind> moved = listed.stream().filter(defaults::contains).toList();
        if (moved.size() < 2) {
            return defaults;
        }
        List<Kind> layers = new ArrayList<>();
        boolean placed = false;
        for (Kind kind : defaults) {
            if (!moved.contains(kind)) {
                layers.add(kind);
            } else if (!placed) {
                layers.addAll(moved);
                placed = true;
            }
        }
        return List.copyOf(layers);
    }

    /** The fallback runs inside the retry's (or hedge's) give-up branch, with the attempts: when that layer is outermost. */
    private static boolean fallbackFused(MethodModel model) {
        return model.fallback() != null && !model.layers().isEmpty()
                && (model.layers().get(0) == Kind.RETRY || model.layers().get(0) == Kind.HEDGE);
    }

    /** True if the circuit breaker's layer is nested inside the retry's. */
    private static boolean breakerInsideRetry(MethodModel model) {
        int retry = model.layers().indexOf(Kind.RETRY);
        return retry >= 0 && model.layers().indexOf(Kind.CIRCUIT_BREAKER) > retry;
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
        TypeElement completionStage = elements.getTypeElement(COMPLETION_STAGE);
        boolean mono = isMono(type) || isFlux(type);
        if (returnType.getKind() != TypeKind.DECLARED || !mono
                && !types.isAssignable(types.erasure(returnType), types.erasure(completionStage.asType()))) {
            return null;
        }
        TypeElement declared = (TypeElement) ((DeclaredType) returnType).asElement();
        List<? extends TypeMirror> arguments = ((DeclaredType) returnType).getTypeArguments();
        String name = declared.getQualifiedName().toString();
        if ((mono || name.equals(COMPLETION_STAGE) || name.equals("java.util.concurrent.CompletableFuture"))
                && arguments.size() == 1) {
            if (arguments.get(0).getKind() != TypeKind.WILDCARD) {
                return arguments.get(0);
            }
            javax.lang.model.type.WildcardType wildcard = (javax.lang.model.type.WildcardType) arguments.get(0);
            if (wildcard.getSuperBound() == null) {
                return wildcard.getExtendsBound() != null ? wildcard.getExtendsBound()
                        : elements.getTypeElement(OBJECT).asType();
            }
        }
        return types.getNoType(TypeKind.NONE);
    }

    /**
     * True if the method returns Reactor's {@code Mono} itself (not a subclass): an asynchronous method
     * whose result is a {@code Mono} instead of a stage. Decided by name, so it needs no Reactor here.
     */
    private static boolean isMono(ExecutableType type) {
        return type.getReturnType() instanceof DeclaredType declared
                && ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(MONO);
    }

    /**
     * True if the method returns Reactor's {@code Flux} itself (not a subclass): an asynchronous stream. Decided
     * by name, so it needs no Reactor here.
     */
    private static boolean isFlux(ExecutableType type) {
        return type.getReturnType() instanceof DeclaredType declared
                && ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(FLUX);
    }

    /** The reactive type among {@link #REACTIVE_TYPES} that {@code returnType} is, or null. */
    private String reactiveType(TypeMirror returnType) {
        if (returnType.getKind() != TypeKind.DECLARED) {
            return null;
        }
        TypeMirror erased = types.erasure(returnType);
        for (String name : REACTIVE_TYPES) {
            TypeElement reactive = elements.getTypeElement(name);
            if (reactive != null && types.isAssignable(erased, types.erasure(reactive.asType()))) {
                return name;
            }
        }
        return null;
    }

    /** True if an asynchronous method returns a stage with a wildcard argument, {@code ? extends X} or {@code ?}. */
    private static boolean isWildcardStage(ExecutableType type) {
        List<? extends TypeMirror> arguments = ((DeclaredType) type.getReturnType()).getTypeArguments();
        return arguments.get(0).getKind() == TypeKind.WILDCARD;
    }

    /**
     * {@code CompletionStage<? extends result>} ({@code Mono<? extends result>} for a {@code Mono} method):
     * what a stage-returning fallback of a wildcard method may return.
     */
    private TypeMirror wildcardStageOf(ExecutableType type, TypeMirror result) {
        return types.getDeclaredType(stageElement(type), types.getWildcardType(result, null));
    }

    /** {@code CompletionStage<result>}, or {@code Mono<result>} for a {@code Mono} method. */
    private TypeMirror stageOf(ExecutableType type, TypeMirror result) {
        return types.getDeclaredType(stageElement(type), result);
    }

    private TypeElement stageElement(ExecutableType type) {
        return elements.getTypeElement(isMono(type) ? MONO : isFlux(type) ? FLUX : COMPLETION_STAGE);
    }

    /** How error messages name the asynchronous type of {@code type}'s method. */
    private static String stageName(ExecutableType type) {
        return isMono(type) ? "Mono" : isFlux(type) ? "Flux" : "CompletionStage";
    }

    /** Validates the attributes and turns them into builder calls. Returns null after reporting errors. */
    @SuppressWarnings("java:S3776") // java:S3776: one flat switch with a case per annotation attribute
    private PolicyModel policyModel(Kind kind, TypeElement iface, ExecutableElement method, ExecutableType type,
            TypeMirror asyncResult, Map<String, AnnotationValue> values) {
        String annotation = "@" + kind.annotation.getSimpleName();
        boolean valid = true;
        String name = "";
        String config = "";
        String classifier = null;
        boolean throwOnExhaustedResult = false;
        boolean locked = false;
        List<String> overrides = new ArrayList<>();
        for (Map.Entry<String, AnnotationValue> entry : values.entrySet()) {
            String attribute = entry.getKey();
            Object value = entry.getValue().getValue();
            String label = annotation + "." + attribute;
            switch (attribute) {
                case "name" -> name = (String) value;
                case "value" -> { // @Timeout("2s")
                    long nanos = durationNanos(method, annotation + "(\"" + value + "\")", value);
                    if (nanos < 0) {
                        valid = false;
                    } else if (nanos == 0) {
                        valid = invalid(method, ProcessorError.DURATION_NOT_POSITIVE, annotation + " must be positive, was \"" + value + "\"");
                    } else {
                        overrides.add("timeout(java.time.Duration.ofNanos(" + nanos + "L))");
                    }
                }
                case "config" -> config = (String) value;
                case "locked" -> locked = (Boolean) value;
                case "enabled" -> overrides.add("enabled(" + value + ")"); // only when written: getElementValues
                case "classifier" -> {
                    if (!(value instanceof TypeMirror classifierType)) {
                        valid = false; // the compiler already reported the unresolvable class
                        continue;
                    }
                    classifier = classifier(iface, method, type, asyncResult, label, classifierType);
                    valid &= classifier != null;
                }
                case "onExhaustedResult" -> throwOnExhaustedResult = constant(value).equals("THROW");
                case "maxAttempts" -> {
                    int number = (Integer) value;
                    if (kind == Kind.HEDGE && (number < 2 || number > 10)) {
                        valid = invalid(method, ProcessorError.HEDGE_ATTEMPTS, label + " must be between 2 and 10, was " + number);
                    } else if (number < 1) {
                        valid = invalid(method, ProcessorError.COUNT_BELOW_ONE, label + " must be at least 1, was " + number);
                    } else {
                        overrides.add(attribute + "(" + number + ")");
                    }
                }
                case "retryOn", "abortOn", "recordOn", "ignoreOn", "hedgeOn", "dropOn" ->
                        overrides.add(attribute + "(java.util.List.of(" + classList(value) + "))");
                case "windowSize", "minimumCalls", "halfOpenCalls", "maxConcurrent", "permits", "initialLimit", "minLimit",
                        "maxLimit" -> {
                    int number = (Integer) value;
                    if (number < 1) {
                        valid = invalid(method, ProcessorError.COUNT_BELOW_ONE, label + " must be at least 1, was " + number);
                    } else {
                        overrides.add(attribute + "(" + number + ")");
                    }
                }
                case "delay", "maxDelay", "maxDuration", "slowCallDuration", "waitInOpen", "maxWaitInHalfOpen", "maxWait" -> {
                    long nanos = durationNanos(method, label + " = \"" + value + "\"", value);
                    if (nanos < 0) {
                        valid = false;
                    } else {
                        overrides.add(attribute + "(java.time.Duration.ofNanos(" + nanos + "L))");
                    }
                }
                case "period" -> {
                    long nanos = durationNanos(method, label + " = \"" + value + "\"", value);
                    if (nanos < 0) {
                        valid = false;
                    } else if (nanos == 0) {
                        valid = invalid(method, ProcessorError.DURATION_NOT_POSITIVE, label + " must be positive, was \"" + value + "\"");
                    } else {
                        overrides.add("period(java.time.Duration.ofNanos(" + nanos + "L))");
                    }
                }
                case "multiplier" -> {
                    double multiplier = (Double) value;
                    if (!Double.isFinite(multiplier) || multiplier < 1) {
                        valid = invalid(method, ProcessorError.INVALID_MULTIPLIER, label + " must be a finite number >= 1, was " + multiplier);
                    } else {
                        overrides.add("multiplier(" + multiplier + ")");
                    }
                }
                case "budgetPercent" -> {
                    double percent = (Double) value;
                    if (!Double.isFinite(percent) || percent < 0) {
                        valid = invalid(method, ProcessorError.INVALID_BUDGET, label + " must be a finite number >= 0, was " + percent);
                    } else {
                        overrides.add("budgetPercent(" + percent + ")");
                    }
                }
                case "budgetMinRetriesPerSecond", "budgetMinHedgesPerSecond" -> {
                    int number = (Integer) value;
                    if (number < 0) {
                        valid = invalid(method, ProcessorError.INVALID_BUDGET, label + " must not be negative, was " + number);
                    } else {
                        overrides.add(attribute + "(" + number + ")");
                    }
                }
                case "windowDuration" -> {
                    long nanos = durationNanos(method, label + " = \"" + value + "\"", value);
                    if (nanos < 0) {
                        valid = false;
                    } else if (nanos < 1_000_000_000L || nanos % 1_000_000_000L != 0
                            || nanos / 1_000_000_000L > Integer.MAX_VALUE) {
                        valid = invalid(method, ProcessorError.WINDOW_MISMATCH, label + " must be a whole number of"
                                + " seconds, at least 1s, was \"" + value + "\": a time window counts calls in one-second"
                                + " buckets.");
                    } else {
                        overrides.add("windowDuration(java.time.Duration.ofSeconds(" + nanos / 1_000_000_000L + "L))");
                    }
                }
                case "window" -> {
                    long nanos = durationNanos(method, label + " = \"" + value + "\"", value);
                    if (nanos < 0) {
                        valid = false;
                    } else if (nanos < 10_000_000L || nanos > 3_600_000_000_000L) {
                        valid = invalid(method, ProcessorError.ADAPTIVE_LIMIT_RANGE, label + " must be between 10ms and 1h, was \""
                                + value + "\"");
                    } else {
                        overrides.add("window(java.time.Duration.ofNanos(" + nanos + "L))");
                    }
                }
                case "budgetWindow" -> {
                    long nanos = durationNanos(method, label + " = \"" + value + "\"", value);
                    if (nanos < 0) {
                        valid = false;
                    } else if (nanos < 1_000_000_000L || nanos > 3_600_000_000_000L) {
                        valid = invalid(method, ProcessorError.INVALID_BUDGET, label + " must be between 1s and 1h, was \"" + value + "\"");
                    } else {
                        overrides.add("budgetWindow(java.time.Duration.ofNanos(" + nanos + "L))");
                    }
                }
                case "failureRateThreshold", "slowCallRateThreshold" -> {
                    double rate = (Double) value;
                    if (!(rate > 0 && rate <= 100)) {
                        valid = invalid(method, ProcessorError.INVALID_PERCENTAGE, label + " must be a percentage > 0 and <= 100, was " + rate);
                    } else {
                        overrides.add(attribute + "(" + rate + ")");
                    }
                }
                case "backoff" -> overrides.add("backoff(" + kind.configType + ".Backoff." + constant(value) + ")");
                case "jitter" -> overrides.add("jitter(" + kind.configType + ".Jitter." + constant(value) + ")");
                case "windowType" -> overrides.add("windowType(" + kind.configType + ".WindowType." + constant(value) + ")");
                case "algorithm" -> overrides.add("algorithm(" + kind.configType + ".Algorithm." + constant(value) + ")");
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
        if (kind == Kind.ADAPTIVE_LIMIT) {
            valid &= limitsInOrder(method, annotation, values);
        }
        if (kind == Kind.CIRCUIT_BREAKER) {
            valid &= windowFitsType(method, annotation, values);
        }
        if (values.containsKey("onExhaustedResult") && !values.containsKey("classifier")) {
            valid = invalid(method, ProcessorError.EXHAUSTED_WITHOUT_CLASSIFIER, annotation + ".onExhaustedResult needs " + annotation + ".classifier: only a"
                    + " classifier can reject a result. Add a classifier, or remove onExhaustedResult.");
        }
        if (!valid) {
            return null;
        }
        String instanceId = iface.getQualifiedName() + "#" + method.getSimpleName() + "(" + erasedParameters(type) + ")";
        // A lock with no attribute set locks nothing: the call stays the same as without it.
        return new PolicyModel(instanceId, name, config, overrides, classifier, throwOnExhaustedResult,
                locked && !overrides.isEmpty());
    }

    /**
     * Checks that {@code windowSize} is set only for a count window and {@code windowDuration} only for a time
     * window, as far as the annotation says. Returns false after reporting.
     */
    private boolean windowFitsType(ExecutableElement method, String annotation, Map<String, AnnotationValue> values) {
        AnnotationValue type = values.get("windowType");
        String windowType = type == null ? null : constant(type.getValue());
        boolean size = values.containsKey("windowSize");
        boolean duration = values.containsKey("windowDuration");
        if (size && duration) {
            return invalid(method, ProcessorError.WINDOW_MISMATCH, annotation + " sets both windowSize and"
                    + " windowDuration: windowSize counts the calls of a COUNT window, windowDuration is the length"
                    + " of a TIME window. Keep the one that fits windowType.");
        }
        if ("TIME".equals(windowType) && size) {
            return invalid(method, ProcessorError.WINDOW_MISMATCH, annotation + ".windowSize counts the calls of a"
                    + " COUNT window, but windowType is TIME: set windowDuration instead, for example"
                    + " windowDuration = \"60s\".");
        }
        if ("COUNT".equals(windowType) && duration) {
            return invalid(method, ProcessorError.WINDOW_MISMATCH, annotation + ".windowDuration is the length of a"
                    + " TIME window, but windowType is COUNT: set windowSize (a number of calls) instead, or"
                    + " windowType = WindowType.TIME.");
        }
        return true;
    }

    /**
     * Checks {@code minLimit <= initialLimit <= maxLimit} among the bounds that the annotation sets; a bound
     * left to the configuration is checked when the registry resolves it. Returns false after reporting.
     */
    private boolean limitsInOrder(ExecutableElement method, String annotation, Map<String, AnnotationValue> values) {
        String[] names = {"minLimit", "initialLimit", "maxLimit"};
        for (int i = 0; i < names.length; i++) {
            for (int j = i + 1; j < names.length; j++) {
                AnnotationValue low = values.get(names[i]);
                AnnotationValue high = values.get(names[j]);
                if (low != null && high != null && (Integer) low.getValue() > (Integer) high.getValue()) {
                    return invalid(method, ProcessorError.ADAPTIVE_LIMIT_RANGE, annotation + "." + names[i] + " ("
                            + low.getValue() + ") must not be greater than " + annotation + "." + names[j] + " ("
                            + high.getValue() + "): the limit stays between minLimit and maxLimit and starts at"
                            + " initialLimit.");
                }
            }
        }
        return true;
    }

    /**
     * Parses the duration of an attribute written as {@code written}. Returns its nanoseconds, or -1 after
     * reporting an invalid duration.
     */
    private long durationNanos(ExecutableElement method, String written, Object value) {
        try {
            return Durations.parseNanos((String) value);
        } catch (IllegalArgumentException e) {
            error(method, ProcessorError.INVALID_DURATION, written + " is not a valid duration: " + e.getMessage()
                    + ". Use " + Durations.GRAMMAR + ".");
            return -1;
        }
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
            return invalidNull(method, ProcessorError.CLASSIFIER_MISSING_CORE, label + ": dev.resilience4cata.Classifier is not on the classpath. Add the"
                    + " resilience4cata-core dependency.");
        }
        if (!isClass(classifier)) {
            return invalidNull(method, ProcessorError.CLASSIFIER_NOT_CLASS, label + ": the classifier must be a class that implements"
                    + " dev.resilience4cata.Classifier<T>.");
        }
        TypeElement classifierClass = (TypeElement) ((DeclaredType) classifier).asElement();
        if (classifierClass.getModifiers().contains(Modifier.ABSTRACT)) {
            return invalidNull(method, ProcessorError.CLASSIFIER_ABSTRACT, label + ": the classifier must not be abstract, because one instance is"
                    + " created per method.");
        }
        if (!classifierClass.getTypeParameters().isEmpty()) {
            return invalidNull(method, ProcessorError.CLASSIFIER_GENERIC, label + ": generic classifier classes are not supported. Implement"
                    + " Classifier<T> with a concrete T.");
        }
        String samePackage = elements.getPackageOf(iface).getQualifiedName().toString();
        TypeElement inaccessible = firstInaccessible(classifierClass, samePackage);
        if (inaccessible != null) {
            return invalidNull(method, ProcessorError.CLASSIFIER_INACCESSIBLE, label + ": " + inaccessible.getSimpleName() + " must be public, or package-private"
                    + " in package " + samePackage + ", for the generated class to create the classifier.");
        }
        if (classifierClass.getNestingKind().isNested() && !classifierClass.getModifiers().contains(Modifier.STATIC)) {
            return invalidNull(method, ProcessorError.CLASSIFIER_INNER, label + ": a nested classifier class must be static.");
        }
        if (!hasNoArgConstructor(classifierClass, samePackage)) {
            return invalidNull(method, ProcessorError.CLASSIFIER_NO_CONSTRUCTOR, label + ": the classifier needs a public no-argument constructor.");
        }
        List<? extends TypeMirror> arguments = supertypeArguments(classifier, types.erasure(classifierType.asType()));
        if (arguments == null) {
            return invalidNull(method, ProcessorError.CLASSIFIER_NOT_CLASSIFIER, label + ": " + classifierClass.getSimpleName()
                    + " does not implement dev.resilience4cata.Classifier<T>.");
        }
        TypeMirror value = asyncResult != null ? asyncResult : boxed(type.getReturnType());
        if (arguments.isEmpty()) {
            return invalidNull(method, ProcessorError.CLASSIFIER_RAW, label + ": " + classifierClass.getSimpleName() + " implements the raw type"
                    + " Classifier. Implement Classifier<" + TypePrinter.print(value) + "> instead.");
        }
        TypeMirror classified = arguments.get(0);
        if (value.getKind() != TypeKind.NONE && !types.isAssignable(value, classified)) {
            return invalidNull(method, ProcessorError.CLASSIFIER_WRONG_TYPE, label + ": " + classifierClass.getSimpleName() + " implements Classifier<"
                    + TypePrinter.print(classified) + ">, but " + method.getSimpleName() + " "
                    + (asyncResult != null ? "completes with " : "returns ") + TypePrinter.print(value)
                    + ". Implement Classifier<" + TypePrinter.print(value) + "> or a supertype of it.");
        }
        return classifierClass.getQualifiedName().toString();
    }

    /** Reports an error and returns null, for the validations that return null after reporting errors. */
    private <T> T invalidNull(Element element, ProcessorError error, String message) {
        error(element, error, message);
        return null;
    }

    /**
     * Validates a {@code @Fallback}. Returns null after reporting errors. {@code asyncResult} is the {@code T}
     * of an asynchronous method, or null for a synchronous one.
     */
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
            return invalidNull(method, ProcessorError.FALLBACK_TARGET, "@Fallback needs exactly one of a handler class, @Fallback(MyFallback.class),"
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
                    checked.returnsValue(), List.of(), checkedThrown, checked.widens(), null);
        }
        List<FallbackTarget> targets = fallbackTargets(iface, method, type, asyncResult, methodName);
        if (targets.isEmpty()) {
            return null;
        }
        // Without a sibling that takes everything, the failures none of them takes are rethrown: the
        // policy must not handle them either, or it would publish FallbackEvent.Applied for them.
        String taken = targets.stream().anyMatch(FallbackTarget::catchesAll) ? null
                : String.join(", ", targets.stream().map(FallbackTarget::exceptionClass).toList());
        return new FallbackModel(instanceId, applyOn, skipOn, null, true, targets, checkedThrown, false, taken);
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
            return invalidNull(method, ProcessorError.HANDLER_MISSING_CORE, label + ": dev.resilience4cata.FallbackHandler is not on the classpath. Add the"
                    + " resilience4cata-core dependency.");
        }
        if (!isClass(handler)) {
            return invalidNull(method, ProcessorError.HANDLER_NOT_CLASS, label + ": the handler must be a class that implements FallbackHandler<T>.");
        }
        TypeElement handlerClass = (TypeElement) ((DeclaredType) handler).asElement();
        if (handlerClass.getModifiers().contains(Modifier.ABSTRACT)) {
            return invalidNull(method, ProcessorError.HANDLER_ABSTRACT, label + ": the handler must not be abstract, because one instance is created per method.");
        }
        if (!handlerClass.getTypeParameters().isEmpty()) {
            return invalidNull(method, ProcessorError.HANDLER_GENERIC, label + ": generic handler classes are not supported. Implement FallbackHandler<T> with a concrete T.");
        }
        String samePackage = elements.getPackageOf(iface).getQualifiedName().toString();
        TypeElement inaccessible = firstInaccessible(handlerClass, samePackage);
        if (inaccessible != null) {
            return invalidNull(method, ProcessorError.HANDLER_INACCESSIBLE, label + ": " + inaccessible.getSimpleName() + " must be public, or package-private in"
                    + " package " + samePackage + ", for the generated class to create the handler.");
        }
        if (handlerClass.getNestingKind().isNested() && !handlerClass.getModifiers().contains(Modifier.STATIC)) {
            return invalidNull(method, ProcessorError.HANDLER_INNER, label + ": a nested handler class must be static.");
        }
        if (!hasNoArgConstructor(handlerClass, samePackage)) {
            return invalidNull(method, ProcessorError.HANDLER_NO_CONSTRUCTOR, label + ": the handler needs a public no-argument constructor.");
        }
        List<? extends TypeMirror> arguments = supertypeArguments(handler, types.erasure(fallbackType.asType()));
        if (arguments == null) {
            return invalidNull(method, ProcessorError.HANDLER_NOT_HANDLER, label + ": " + handlerClass.getSimpleName() + " does not implement dev.resilience4cata.FallbackHandler<T>.");
        }
        if (arguments.isEmpty()) {
            return invalidNull(method, ProcessorError.HANDLER_RAW, label + ": " + handlerClass.getSimpleName() + " implements the raw type Fallback."
                    + " Implement FallbackHandler<" + TypePrinter.print(boxed(type.getReturnType())) + "> instead.");
        }
        TypeMirror result = arguments.get(0);
        TypeMirror returnType = type.getReturnType();
        String name = handlerClass.getQualifiedName().toString();
        if (asyncResult != null) {
            return asyncHandler(method, type, asyncResult, label, handlerClass, result);
        }
        boolean fits = returnType.getKind() == TypeKind.VOID || types.isAssignable(result, returnType);
        if (!fits && mentions(returnType, type.getTypeVariables())) {
            return invalidNull(method, ProcessorError.HANDLER_ON_GENERIC_METHOD, genericHandler(label, handlerClass, result, method, returnType));
        }
        if (!fits) {
            return invalidNull(method, ProcessorError.HANDLER_WRONG_TYPE, label + ": " + handlerClass.getSimpleName() + " implements FallbackHandler<"
                    + TypePrinter.print(result) + ">, but " + method.getSimpleName() + " returns "
                    + TypePrinter.print(returnType) + ". Implement FallbackHandler<" + TypePrinter.print(boxed(returnType)) + ">.");
        }
        return new Handler(name, true, false);
    }

    /** Checks the type of a handler of an asynchronous method: {@code T}, or a stage of it. */
    private Handler asyncHandler(ExecutableElement method, ExecutableType type, TypeMirror asyncResult, String label,
            TypeElement handlerClass, TypeMirror result) {
        String name = handlerClass.getQualifiedName().toString();
        if (types.isAssignable(result, stageOf(type, asyncResult))) {
            return new Handler(name, false, false);
        }
        if (types.isAssignable(result, wildcardStageOf(type, asyncResult))) {
            return new Handler(name, false, true);
        }
        if (types.isAssignable(result, asyncResult)) {
            return new Handler(name, true, false);
        }
        TypeMirror returnType = type.getReturnType();
        if (mentions(asyncResult, type.getTypeVariables())) {
            return invalidNull(method, ProcessorError.HANDLER_ON_GENERIC_METHOD, genericHandler(label, handlerClass, result, method, returnType));
        }
        return invalidNull(method, ProcessorError.HANDLER_WRONG_TYPE, label + ": " + handlerClass.getSimpleName() + " implements FallbackHandler<"
                + TypePrinter.print(result) + ">, but " + method.getSimpleName() + " returns "
                + TypePrinter.print(returnType) + ". Implement FallbackHandler<" + TypePrinter.print(asyncResult)
                + "> or FallbackHandler<" + stageElement(type).getQualifiedName() + "<" + TypePrinter.print(asyncResult) + ">>.");
    }

    /** True if {@code type} is a class (not an interface, enum, record or annotation type). */
    private static boolean isClass(TypeMirror type) {
        return type.getKind() == TypeKind.DECLARED && ((DeclaredType) type).asElement().getKind() == ElementKind.CLASS;
    }

    /** The first of {@code type} and its enclosing types that the generated class cannot access, or null. */
    private static TypeElement firstInaccessible(TypeElement type, String samePackage) {
        for (Element e = type; e instanceof TypeElement t; e = t.getEnclosingElement()) {
            if (!accessible(t, samePackage)) {
                return t;
            }
        }
        return null;
    }

    /** True if the generated class can call a no-argument constructor of {@code type}. */
    private static boolean hasNoArgConstructor(TypeElement type, String samePackage) {
        for (ExecutableElement c : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            if (c.getParameters().isEmpty() && accessible(c, type, samePackage)) {
                return true;
            }
        }
        return false;
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
    @SuppressWarnings("java:S1168") // java:S1168: null (not a subtype) and empty (raw subtype) mean different things
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

    /** Finds the sibling fallback methods, most specific exception first. Returns none after reporting an error. */
    private List<FallbackTarget> fallbackTargets(TypeElement iface, ExecutableElement method, ExecutableType type,
            TypeMirror asyncResult, String name) {
        List<FallbackTarget> targets = new ArrayList<>();
        Map<FallbackTarget, TypeMirror> exceptionTypes = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Set<String> names = new TreeSet<>(); // every method that could be a fallback, for "did you mean"
        boolean named = false;
        for (ExecutableElement candidate : ElementFilter.methodsIn(elements.getAllMembers(iface))) {
            if (mayBeFallback(iface, method, candidate)) {
                names.add(candidate.getSimpleName().toString());
                if (candidate.getSimpleName().contentEquals(name)) {
                    named = true;
                    TypedTarget found = fallbackTarget(iface, method, type, asyncResult, candidate, problems);
                    if (found != null) {
                        targets.add(found.target());
                        exceptionTypes.put(found.target(), found.exception());
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            reportNoFallbackTarget(iface, method, type, name, named, problems, names);
            return targets;
        }
        // Most specific first: a subtype is always deeper in the hierarchy. Methods without an exception parameter go last.
        targets.sort(Comparator.comparingInt((FallbackTarget t) -> t.exceptionType() == null ? Integer.MIN_VALUE
                : depth(exceptionTypes.get(t))).reversed());
        return targets;
    }

    /** A usable sibling fallback method, and the type of its exception parameter (null without one). */
    private record TypedTarget(FallbackTarget target, TypeMirror exception) {
    }

    /** True if {@code candidate}, a member of {@code iface}, is a method other than {@code method} that may be its fallback. */
    private static boolean mayBeFallback(TypeElement iface, ExecutableElement method, ExecutableElement candidate) {
        return !candidate.equals(method) && (candidate.getEnclosingElement().getKind() == ElementKind.INTERFACE
                || (iface.getKind() == ElementKind.CLASS && !isObject(candidate.getEnclosingElement())));
    }

    /**
     * Checks a sibling method with the fallback's name. Returns null if it is not usable, after adding the
     * reason to {@code problems} when it matches closely enough for the reason to help.
     */
    @SuppressWarnings("java:S3776") // java:S3776: one candidate's checks, in the order their problems are reported
    private TypedTarget fallbackTarget(TypeElement iface, ExecutableElement method, ExecutableType type,
            TypeMirror asyncResult, ExecutableElement candidate, List<String> problems) {
        List<? extends TypeMirror> parameters = type.getParameterTypes();
        TypeMirror throwable = elements.getTypeElement("java.lang.Throwable").asType();
        TypeMirror returnType = type.getReturnType();
        String name = candidate.getSimpleName().toString();
        ExecutableType candidateType = (ExecutableType) types.asMemberOf((DeclaredType) iface.asType(), candidate);
        List<? extends TypeMirror> candidateParameters = candidateType.getParameterTypes();
        int n = parameters.size();
        if (candidateParameters.size() != n && candidateParameters.size() != n + 1) {
            return null;
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
            return null;
        }
        boolean sameParameters = true;
        for (int i = 0; i < n; i++) {
            sameParameters &= types.isSameType(substitute(candidateParameters.get(i), renaming), parameters.get(i));
        }
        TypeMirror exception = candidateParameters.size() == n + 1 ? substitute(candidateParameters.get(n), renaming) : null;
        if (!sameParameters || (exception != null && !types.isAssignable(exception, throwable))) {
            return null;
        }
        if (exception != null && exception.getKind() != TypeKind.DECLARED) {
            problems.add(signature + " takes a " + TypePrinter.print(exception) + " as its exception parameter, which"
                    + " is not a class: the generated code cannot test a failure against it.");
            return null;
        }
        Set<Modifier> modifiers = candidate.getModifiers();
        if (modifiers.contains(Modifier.PRIVATE)) {
            problems.add(signature + " is private, so the generated class cannot call it. Make it "
                    + (iface.getKind() == ElementKind.CLASS ? "package-private, protected or public." : "a default"
                    + " or static method."));
            return null;
        }
        if (iface.getKind() == ElementKind.CLASS && !modifiers.contains(Modifier.PUBLIC)
                && !modifiers.contains(Modifier.PROTECTED) && !packageOf(candidate).equals(packageOf(iface))) {
            problems.add(signature + " is package-private in another package, so the generated class cannot call"
                    + " it. Make it protected or public.");
            return null;
        }
        TypeMirror candidateReturn = substitute(candidateType.getReturnType(), renaming);
        boolean returnsValue = true;
        boolean widens = false;
        if (asyncResult != null) {
            // An async fallback may return the value or a stage of it; it may throw anything, which fails the future.
            returnsValue = !types.isAssignable(candidateReturn, stageOf(type, asyncResult));
            if (returnsValue && types.isAssignable(candidateReturn, wildcardStageOf(type, asyncResult))) {
                returnsValue = false;
                widens = true;
            }
            if (returnsValue && !types.isAssignable(candidateReturn, asyncResult)) {
                problems.add(signature + " returns " + TypePrinter.print(candidateReturn) + ", which is neither "
                        + TypePrinter.print(asyncResult) + " nor a " + stageName(type) + " of it.");
                return null;
            }
        } else if (returnType.getKind() == TypeKind.VOID ? candidateReturn.getKind() != TypeKind.VOID
                : !types.isAssignable(candidateReturn, returnType)) {
            problems.add(signature + " returns " + TypePrinter.print(candidateReturn) + ", which is not "
                    + (returnType.getKind() == TypeKind.VOID ? "void" : "assignable to " + TypePrinter.print(returnType)) + ".");
            return null;
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
            return null;
        }
        boolean catchesAll = exception == null || types.isSameType(exception, throwable);
        // Explicit type arguments, so that the call does not depend on inference.
        String typeArguments = type.getTypeVariables().isEmpty() ? ""
                : "<" + TypePrinter.join(type.getTypeVariables(), ", ") + ">";
        FallbackTarget target = new FallbackTarget(fallbackReceiver(iface, candidate), typeArguments + name,
                exception == null ? null : TypePrinter.print(exception),
                exception == null ? null : TypePrinter.print(types.erasure(exception)) + ".class",
                catchesAll, returnsValue, widens);
        return new TypedTarget(target, exception);
    }

    /** What the generated code calls a sibling fallback method on. */
    private static String fallbackReceiver(TypeElement iface, ExecutableElement candidate) {
        if (candidate.getModifiers().contains(Modifier.STATIC)) {
            return ((TypeElement) candidate.getEnclosingElement()).getQualifiedName().toString();
        }
        return iface.getKind() == ElementKind.CLASS ? "this" : DELEGATE;
    }

    /**
     * Reports that no sibling method can be the fallback: none has its name (not {@code named}), or none of
     * those is usable, for the reasons in {@code problems}.
     */
    private void reportNoFallbackTarget(TypeElement iface, ExecutableElement method, ExecutableType type, String name,
            boolean named, List<String> problems, Set<String> names) {
        TypeMirror returnType = type.getReturnType();
        String parameterList = TypePrinter.join(type.getParameterTypes(), ", ");
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
            return;
        }
        StringBuilder message = new StringBuilder("@Fallback(method = \"" + name + "\"): no usable method " + name
                + " in " + iface.getSimpleName() + ". " + expectation);
        for (String problem : problems) {
            message.append("\n  ").append(problem);
        }
        error(method, ProcessorError.FALLBACK_METHOD_UNUSABLE, message.toString());
    }

    /**
     * Maps the candidate's method type variables to the method's, by position, if both declare the same number
     * with the same bounds after renaming. Returns null if they do not; an empty map for two non-generic methods.
     */
    @SuppressWarnings("java:S1168") // java:S1168: null (no match) and empty (two non-generic methods) differ
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
        delegateReceiver = subclass ? "super" : DELEGATE;
        if (subclass) {
            if (micronautBeans) {
                appendMicronautBean(out, iface);
            }
            out.append("@SuppressWarnings({\"deprecation\", \"removal\", \"serial\"})\n");
            out.append("public class ").append(simpleName).append(typeParameters(iface.getTypeParameters()))
                    .append(" extends ").append(ifaceType).append(" {\n\n");
        } else {
            out.append("@SuppressWarnings({\"deprecation\", \"removal\"})\n");
            out.append("public final class ").append(simpleName).append(typeParameters(iface.getTypeParameters()))
                    .append(" implements ").append(ifaceType).append(" {\n\n");
            appendField(out, ifaceType, "delegate");
        }
        appendFields(out, methods);
        if (subclass) {
            for (SubclassWriter.Constructor constructor : subclasses.constructors(iface)) { // mirrors every constructor
                appendConstructorStart(out, simpleName, constructor);
                appendFieldInits(out, simpleName, methods, constructor.registry());
                out.append("    }\n");
            }
        } else {
            out.append("\n    public ").append(simpleName).append("(").append(ifaceType).append(" delegate, ")
                    .append(REGISTRY_TYPE).append(" registry) {\n");
            out.append("        this.delegate = java.util.Objects.requireNonNull(delegate, \"delegate\");\n");
            appendFieldInits(out, simpleName, methods, "registry");
            out.append("    }\n");
        }
        for (int i = 0; i < methods.size(); i++) {
            writeMethod(out, methods.get(i), i);
        }
        out.append("}\n");

        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        writeSource(iface, qualifiedName, out.toString());
        writeNativeImageMetadata(iface, binaryName, qualifiedName, subclass);
    }

    /**
     * Writes the GraalVM reachability metadata that lets {@code Resilient.of} and {@code Resilient.create} find the
     * generated class and its public constructors by reflection in a native image, with no user configuration:
     * {@code META-INF/native-image/dev.resilience4cata.generated/<generated class>/reachability-metadata.json}.
     * Native Image reads every file with that name under {@code META-INF/native-image} on the class path, so one
     * file per generated class keeps {@code iface} its only originating element (isolating).
     *
     * <p>The entry is conditional: for an interface, on the interface being reached, which happens before
     * {@code Resilient.of} can be called with an implementation of it; for a class, on {@code Resilient} itself,
     * because {@code Resilient.create(C.class, ...)} may run before {@code C} is initialized. Array types of varargs
     * parameters are registered too, for the array {@code Resilient.create} builds to pass the trailing arguments.
     */
    private void writeNativeImageMetadata(TypeElement iface, String binaryName, String generatedName, boolean subclass) {
        String condition = subclass ? "dev.resilience4cata.Resilient" : binaryName;
        Set<String> arrays = new TreeSet<>();
        if (subclass) {
            for (ExecutableElement c : ElementFilter.constructorsIn(iface.getEnclosedElements())) {
                if (c.isVarArgs() && !c.getModifiers().contains(Modifier.PRIVATE)) {
                    arrays.add(runtimeName(types.erasure(c.getParameters().get(c.getParameters().size() - 1).asType())));
                }
            }
        }
        StringBuilder json = new StringBuilder("{\n  \"reflection\": [\n");
        json.append("    {\n      \"condition\": {\"typeReached\": \"").append(condition).append("\"},\n")
                .append("      \"type\": \"").append(generatedName).append("\",\n")
                .append("      \"allPublicConstructors\": true\n    }");
        for (String array : arrays) {
            json.append(",\n    {\n      \"condition\": {\"typeReached\": \"").append(condition).append("\"},\n")
                    .append("      \"type\": \"").append(array).append("\"\n    }");
        }
        json.append("\n  ]\n}\n");
        String path = "META-INF/native-image/dev.resilience4cata.generated/" + generatedName + "/reachability-metadata.json";
        try {
            javax.tools.FileObject file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", path, iface);
            try (Writer writer = file.openWriter()) {
                writer.write(json.toString());
            }
        } catch (IOException e) {
            error(iface, ProcessorError.WRITE_FAILED, "Could not write " + path + ", the native-image metadata of "
                    + generatedName + ": " + e.getMessage() + ". Check that the class output directory is writable and"
                    + " that no other processor writes the same file.");
        }
    }

    /** The name Native Image uses for {@code type}: binary names, with {@code []} for arrays. */
    private String runtimeName(TypeMirror type) {
        return switch (type.getKind()) {
            case ARRAY -> runtimeName(((javax.lang.model.type.ArrayType) type).getComponentType()) + "[]";
            case DECLARED -> elements.getBinaryName((TypeElement) ((DeclaredType) type).asElement()).toString();
            default -> TypePrinter.print(type);
        };
    }

    /** The {@code final} fields of the generated class: per method, its fallback, handler, policies and classifiers. */
    private static void appendFields(StringBuilder out, List<MethodModel> methods) {
        for (int i = 0; i < methods.size(); i++) {
            FallbackModel fallback = methods.get(i).fallback();
            if (fallback != null) {
                appendField(out, Kind.FALLBACK.policyType, "fallback" + i);
                if (fallback.handler() != null) {
                    appendField(out, fallback.handler(), "fallbackHandler" + i);
                }
            }
            for (Kind kind : methods.get(i).policies().keySet()) {
                appendField(out, kind.policyType, kind.field + i);
                if (ownsClassifier(methods.get(i), kind, i)) {
                    appendField(out, methods.get(i).policies().get(kind).classifier(), classifierField(methods.get(i), kind, i));
                }
            }
        }
    }

    private static void appendField(StringBuilder out, String type, String name) {
        out.append("    private final ").append(type).append(' ').append(name).append(";\n");
    }

    /** The constructor statements that set the fields written by {@link #appendFields}. */
    private static void appendFieldInits(StringBuilder out, String simpleName, List<MethodModel> methods,
            String registry) {
        out.append("        java.util.Objects.requireNonNull(").append(registry).append(", \"registry\");\n");
        out.append("        dev.resilience4cata.generated.Abi.require(").append(GENERATED_CODE_LEVEL).append(", ")
                .append(simpleName).append(".class);\n");
        if (methods.stream().anyMatch(m -> (!m.policies().isEmpty() || m.fallback() != null)
                && (isMono(m.type()) || isFlux(m.type())))) {
            out.append("        dev.resilience4cata.reactor.generated.ReactorAbi.require(").append(GENERATED_CODE_LEVEL)
                    .append(", ").append(simpleName).append(".class);\n");
        }
        for (int i = 0; i < methods.size(); i++) {
            FallbackModel fallback = methods.get(i).fallback();
            if (fallback != null) {
                out.append("        this.fallback").append(i).append(" = ").append(POLICIES).append(".fallback(")
                        .append(registry).append(", ").append(literal(fallback.instanceId())).append(", java.util.List.of(")
                        .append(fallback.applyOn()).append("), java.util.List.of(").append(fallback.skipOn()).append("), ")
                        .append(fallback.taken() == null ? "null" : "java.util.List.of(" + fallback.taken() + ")")
                        .append(");\n");
                if (fallback.handler() != null) {
                    out.append("        this.fallbackHandler").append(i).append(" = new ").append(fallback.handler()).append("();\n");
                }
            }
            for (Map.Entry<Kind, PolicyModel> entry : methods.get(i).policies().entrySet()) {
                Kind kind = entry.getKey();
                PolicyModel policy = entry.getValue();
                out.append("        this.").append(kind.field).append(i).append(" = ").append(POLICIES).append('.')
                        .append(kind.registryMethod).append('(').append(registry).append(", ")
                        .append(literal(policy.instanceId())).append(", ").append(literal(policy.name()))
                        .append(", ").append(literal(policy.config())).append(", ").append(overrides(policy.overrides()))
                        .append(", ").append(policy.locked()).append(");\n");
                if (ownsClassifier(methods.get(i), kind, i)) {
                    out.append("        this.").append(classifierField(methods.get(i), kind, i)).append(" = new ")
                            .append(policy.classifier()).append("();\n");
                }
            }
        }
    }

    /**
     * With {@code -Ar4c.inject=micronaut}: the class's own type annotations (its scope, qualifiers,
     * {@code @Requires}, {@code @Replaces}...), so that Micronaut creates the generated subclass as it would have
     * created {@code C}. {@code C}'s own definition is left out by the Micronaut integration
     * ({@code AnnotatedClassesFilter}), not by a {@code @Replaces(C.class)} here: that would also replace every subclass
     * of {@code C} and every {@code @MockBean} of it. Reads only {@code type} and the class path, so the processor
     * stays isolating.
     */
    private void appendMicronautBean(StringBuilder out, TypeElement type) {
        if (elements.getTypeElement(MICRONAUT_REPLACES) == null) {
            error(type, ProcessorError.INVALID_INJECT_OPTION, "The compiler option -A" + INJECT_OPTION + "="
                    + INJECT_MICRONAUT + " is set, but " + MICRONAUT_REPLACES + " is not on the compile class path, so "
                    + type.getSimpleName() + SUFFIX + " cannot be a Micronaut bean in place of " + type.getSimpleName() + "."
                    + " Add Micronaut (io.micronaut:micronaut-inject) to the compile class path, or remove the option.");
            return;
        }
        out.append(AnnotationPrinter.typeAnnotations(type, "", "\n"));
        out.append("@dev.resilience4cata.generated.ContainerBean\n");
    }

    /** Writes the generated class, with {@code iface} as its only originating element. */
    private void writeSource(TypeElement iface, String qualifiedName, String source) {
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, iface);
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException e) {
            error(iface, ProcessorError.WRITE_FAILED, "Could not write " + qualifiedName + ": " + e.getMessage()
                    + ". Check that the generated-sources directory is writable and that no other processor or source"
                    + " file defines " + qualifiedName + ".");
        }
    }

    /** Opens a constructor of a generated subclass: the registry, then the class constructor's parameters. */
    private static void appendConstructorStart(StringBuilder out, String simpleName, SubclassWriter.Constructor constructor) {
        out.append("\n    ").append(constructor.annotations()).append("public ").append(constructor.typeVariables())
                .append(simpleName).append("(").append(REGISTRY_TYPE).append(' ').append(constructor.registry())
                .append(constructor.parameters()).append(")").append(constructor.throwsClause())
                .append(" {\n");
        out.append("        super(").append(constructor.arguments()).append(");\n");
    }

    private static boolean isObject(Element type) {
        return ((TypeElement) type).getQualifiedName().contentEquals(OBJECT);
    }

    private void writeMethod(StringBuilder out, MethodModel model, int index) {
        ExecutableElement method = model.method();
        ExecutableType type = model.type();
        List<? extends TypeMirror> parameterTypes = type.getParameterTypes();
        List<String> parameters = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        // The subclass keeps the parameter names (frameworks read them on the override); the interface wrapper's
        // output stays as it was, since frameworks read the interface.
        boolean subclass = !DELEGATE.equals(delegateReceiver);
        addParameters(method, parameterTypes, parameters, arguments, subclass ? staticReceivers(model) : null);
        String returnType = TypePrinter.print(type.getReturnType());
        boolean isVoid = type.getReturnType().getKind() == TypeKind.VOID;
        String typeVariables = type.getTypeVariables().isEmpty() ? "" : methodTypeVariables(type.getTypeVariables()) + " ";
        String throwsClause = type.getThrownTypes().isEmpty() ? "" : " throws " + TypePrinter.join(type.getThrownTypes(), ", ");
        String signatureTail = "(" + String.join(", ", parameters) + ")" + throwsClause;
        String args = String.join(", ", arguments);
        String delegateCall = delegateReceiver + "." + method.getSimpleName() + "(" + args + ")";
        // The subclass's override repeats the annotations of the method it overrides and of its parameters, for
        // frameworks that read them on the invoked method only (@Transactional, interceptor bindings).
        String annotations = subclass ? AnnotationPrinter.methodAnnotations(method, "\n    ") : "";
        String publicTail = subclass ? "(" + String.join(", ", annotatedParameters(method, parameters)) + ")" + throwsClause
                : signatureTail;

        if (model.asyncResult() != null && (!model.policies().isEmpty() || model.fallback() != null)) {
            writeAsyncMethod(out, model, index, typeVariables, returnType, annotations + "public ",
                    method.getSimpleName() + publicTail, String.join(", ", parameters), arguments, delegateCall);
            return;
        }
        // One layer per policy, outermost first. Layer i calls layer i + 1; the last one calls the delegate.
        List<Kind> layers = new ArrayList<>(model.layers());
        if (model.fallback() != null && !fallbackFused(model)) {
            layers.add(0, Kind.FALLBACK); // with a retry outermost, the fallback is part of the retry layer instead
        }
        List<String> layerNames = new ArrayList<>();
        for (Kind kind : layers) {
            layerNames.add("r4c$" + kind.field + index);
        }
        out.append("\n    @Override\n    ").append(annotations).append("public ").append(typeVariables).append(returnType)
                .append(' ').append(method.getSimpleName()).append(publicTail).append(" {\n");
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

    /** {@code parameters} with the annotations of {@code method}'s parameters in front of each. */
    private static List<String> annotatedParameters(ExecutableElement method, List<String> parameters) {
        List<String> annotated = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            annotated.add(AnnotationPrinter.parameterAnnotations(method.getParameters().get(i)) + parameters.get(i));
        }
        return annotated;
    }

    /**
     * The leftmost names of the static fallback methods' receivers ({@code demo} in {@code demo.Service.fixed(...)}):
     * a parameter with one of these names would obscure them.
     */
    private static Set<String> staticReceivers(MethodModel model) {
        Set<String> names = new HashSet<>();
        if (model.fallback() != null) {
            for (FallbackTarget target : model.fallback().targets()) {
                if (!target.receiver().equals("this") && !target.receiver().equals(DELEGATE)) {
                    names.add(target.receiver().split("\\.", 2)[0]);
                }
            }
        }
        return names;
    }

    /**
     * Adds the declarations and the arguments of a generated method: {@code T p0, U p1, ...} in an interface wrapper
     * ({@code reserved} null), the declared names ({@link SubclassWriter#parameterName}) in a subclass, renaming those
     * in {@code reserved} too.
     */
    private static void addParameters(ExecutableElement method, List<? extends TypeMirror> parameterTypes,
            List<String> parameters, List<String> arguments, Set<String> reserved) {
        for (int i = 0; i < parameterTypes.size(); i++) {
            String parameterType = TypePrinter.print(parameterTypes.get(i));
            if (method.isVarArgs() && i == parameterTypes.size() - 1) {
                parameterType = parameterType.substring(0, parameterType.length() - 2) + "...";
            }
            String name = reserved != null ? SubclassWriter.parameterName(method.getParameters().get(i), i, reserved) : "p" + i;
            parameters.add(parameterType + " " + name);
            arguments.add(name);
        }
    }

    /**
     * An asynchronous method is one expression: each policy's {@code executeAsync} gets the next layer
     * as a lambda, innermost first. A fallback becomes a private {@code r4c$recover<index>} method that
     * returns a stage; with a retry, it is passed to the retry so that it runs when the retries give up.
     * For a method that returns a stage of {@code ? extends X} (or {@code ?}), the policies work with
     * {@code X}: the delegate's stage is widened with {@code AsyncCall.widen}.
     */
    @SuppressWarnings("java:S107") // java:S107: the parts of the signature that writeMethod already computed
    private static void writeAsyncMethod(StringBuilder out, MethodModel model, int index, String typeVariables,
            String returnType, String modifiers, String signature, String parameters, List<String> arguments,
            String delegateCall) {
        if (isFlux(model.type())) {
            writeFluxMethod(out, model, index, typeVariables, returnType, modifiers, signature, parameters, arguments,
                    delegateCall);
            return;
        }
        FallbackModel fallback = model.fallback();
        String args = String.join(", ", arguments);
        String result = TypePrinter.print(model.asyncResult());
        String recoverTypeArguments = model.type().getTypeVariables().isEmpty() ? ""
                : "<" + TypePrinter.join(model.type().getTypeVariables(), ", ") + ">";
        boolean mono = isMono(model.type());
        // A Mono method's policies run inside Monos.defer, once per subscription, with the subscriber's context,
        // which the delegate's Mono and a fallback's Mono are subscribed with.
        String recovery = "(r4c$e, r4c$attempts) -> this." + recoverTypeArguments + "r4c$recover" + index
                + "(r4c$e, r4c$attempts" + (mono ? ", r4c$subscriberContext" : "") + (args.isEmpty() ? "" : ", " + args) + ")";
        String delegate = mono ? monoStage(delegateCall, result)
                : isWildcardStage(model.type()) ? widen(delegateCall, result) : delegateCall;
        String expression = asyncExpression(model, index, delegate, recovery);
        if (mono) {
            expression = MONOS + ".<" + result + ">defer(r4c$subscriberContext -> " + expression + ")";
        }
        out.append("\n    @Override\n    ").append(modifiers).append(typeVariables).append(returnType).append(' ')
                .append(signature).append(" {\n");
        appendLines(out, List.of("return " + expression + ";"));
        out.append("    }\n");
        if (fallback == null) {
            return;
        }
        String stage = COMPLETION_STAGE + "<" + result + ">";
        out.append("\n    private ").append(typeVariables).append(stage).append(" r4c$recover").append(index)
                .append("(java.lang.Throwable r4c$e, int r4c$attempts")
                .append(mono ? ", reactor.util.context.ContextView r4c$subscriberContext" : "")
                .append(parameters.isEmpty() ? "" : ", " + parameters)
                .append(") throws java.lang.Throwable {\n");
        appendLines(out, asyncFallbackLines(fallback, index, arguments, result, mono ? AsyncStyle.MONO : AsyncStyle.STAGE));
        out.append("    }\n");
    }

    /**
     * A {@code Flux} method: the policies run inside {@code Fluxes.defer}, once per subscription, with the call's
     * {@code FluxCall} ({@code r4c$stream}). The layers inside the retry or the fallback are one attempt
     * ({@code r4c$stream.attempt(r4c$attempt -> ...)}), so that the retry and the fallback act only before the
     * stream's first element (without either, all the layers are the only attempt, {@code r4c$stream.single}); the timeout, breaker and adaptive limit use their streaming form
     * with that attempt; the delegate's {@code Flux} is subscribed by {@code r4c$attempt.subscribe}. The order is
     * always the default one ({@link #checkFlux}).
     */
    @SuppressWarnings("java:S107") // java:S107: the parts of the signature that writeMethod already computed
    private static void writeFluxMethod(StringBuilder out, MethodModel model, int index, String typeVariables,
            String returnType, String modifiers, String signature, String parameters, List<String> arguments,
            String delegateCall) {
        FallbackModel fallback = model.fallback();
        String args = String.join(", ", arguments);
        String result = TypePrinter.print(model.asyncResult());
        String recoverTypeArguments = model.type().getTypeVariables().isEmpty() ? ""
                : "<" + TypePrinter.join(model.type().getTypeVariables(), ", ") + ">";
        String recovery = "(r4c$e, r4c$attempts) -> this." + recoverTypeArguments + "r4c$recover" + index
                + "(r4c$e, r4c$attempts, r4c$stream" + (args.isEmpty() ? "" : ", " + args) + ")";
        boolean fused = fallbackFused(model);
        String expression = "r4c$attempt.subscribe(" + delegateCall + ")";
        List<Kind> layers = model.layers();
        boolean bounded = false;
        for (int i = layers.size() - 1; i >= 0; i--) {
            Kind kind = layers.get(i);
            String field = "this." + kind.field + index;
            expression = switch (kind) {
                case TIMEOUT, CIRCUIT_BREAKER, ADAPTIVE_LIMIT ->
                        field + ".executeStreamAsync(() -> " + expression + ", r4c$attempt)";
                case RETRY -> field + ".executeAsync(() -> " + boundary(expression)
                        + (fused ? ", this.fallback" + index + ", " + recovery : "") + ")";
                case BULKHEAD, RATE_LIMITER -> field + EXECUTE_ASYNC + expression + ")";
                case FALLBACK, HEDGE -> throw new IllegalStateException(kind + " is not a Flux layer (checkFlux)");
            };
            bounded |= kind == Kind.RETRY;
        }
        if (fallback != null && !fused) {
            expression = "this.fallback" + index + ".executeAsync(() -> " + boundary(expression) + ", " + recovery + ")";
            bounded = true;
        }
        if (!bounded) {
            expression = "r4c$stream.single(r4c$attempt -> " + expression + ")"; // nothing acts on the outcome: no boundary
        }
        expression = FLUXES + ".<" + result + ">defer(r4c$stream -> " + expression + ")";
        out.append("\n    @Override\n    ").append(modifiers).append(typeVariables).append(returnType).append(' ')
                .append(signature).append(" {\n");
        appendLines(out, List.of("return " + expression + ";"));
        out.append("    }\n");
        if (fallback == null) {
            return;
        }
        out.append("\n    private ").append(typeVariables).append(COMPLETION_STAGE).append("<java.lang.Void> r4c$recover")
                .append(index).append("(java.lang.Throwable r4c$e, int r4c$attempts, ")
                .append("dev.resilience4cata.reactor.generated.FluxCall<").append(result).append("> r4c$stream")
                .append(parameters.isEmpty() ? "" : ", " + parameters)
                .append(") throws java.lang.Throwable {\n");
        appendLines(out, asyncFallbackLines(fallback, index, arguments, result, AsyncStyle.FLUX));
        out.append("    }\n");
    }

    /** One attempt of a {@code Flux} method: {@code layers} run with their own {@code r4c$attempt}. */
    private static String boundary(String layers) {
        return "r4c$stream.attempt(r4c$attempt -> " + layers + ")";
    }

    /** What an asynchronous fallback's result becomes: a stage, a {@code Mono}'s stage, or a stream of the call. */
    private enum AsyncStyle {
        STAGE, MONO, FLUX
    }

    /** The stage of a {@code Mono} that a delegate or a fallback returned, subscribed with the caller's context. */
    private static String monoStage(String mono, String result) {
        return MONOS + ".<" + result + ">stage(" + mono + ", r4c$subscriberContext)";
    }

    /**
     * Wraps the delegate's call in each policy's {@code executeAsync}, innermost first, and in the fallback's
     * if there is no retry to run it.
     */
    private static String asyncExpression(MethodModel model, int index, String delegateCall, String recovery) {
        List<Kind> layers = model.layers();
        StringBuilder expression = new StringBuilder(delegateCall);
        for (int i = layers.size() - 1; i >= 0; i--) {
            Kind kind = layers.get(i);
            expression.insert(0, "this." + kind.field + index + EXECUTE_ASYNC)
                    .append(asyncArguments(model, kind, index, recovery)).append(')');
        }
        if (model.fallback() != null && !fallbackFused(model)) {
            expression.insert(0, "this.fallback" + index + EXECUTE_ASYNC).append(", ").append(recovery).append(')');
        }
        return expression.toString();
    }

    /** The arguments after the call lambda of {@code kind}'s {@code executeAsync}, each preceded by a comma. */
    private static String asyncArguments(MethodModel model, Kind kind, int index, String recovery) {
        String classifier = classifierField(model, kind, index);
        boolean fallback = fallbackFused(model);
        if (classifier != null && kind == Kind.RETRY) {
            return ", this." + classifier + ", " + (model.policies().get(kind).throwOnExhaustedResult() ? "true, " : "")
                    + (fallback ? "this.fallback" + index + ", " + recovery : "null, null");
        }
        if (classifier != null) {
            return ", this." + classifier + (breakerInsideRetry(model) ? ", true" : "");
        }
        if ((kind == Kind.RETRY || kind == Kind.HEDGE) && fallback) {
            return ", this.fallback" + index + ", " + recovery; // the hedge, like the retry, runs it with the attempts
        }
        return "";
    }

    /**
     * The body of an async fallback: returns a stage of {@code result}, or throws to fail the call.
     * {@code handles} was already checked.
     */
    private static List<String> asyncFallbackLines(FallbackModel fallback, int index, List<String> arguments,
            String result, AsyncStyle style) {
        List<String> lines = new ArrayList<>();
        if (fallback.handler() != null) {
            lines.add("final dev.resilience4cata.FallbackContext r4c$context = this.fallback" + index
                    + ".context(r4c$e, r4c$attempts, new java.lang.Object[] {" + String.join(", ", arguments) + "});");
            String apply = "this.fallbackHandler" + index + ".apply(r4c$context)";
            lines.add("return " + recoveryStage(style, apply, fallback.handlerReturnsValue(), fallback.handlerWidens(),
                    result) + ";");
            return lines;
        }
        for (int k = 0; k < fallback.targets().size(); k++) {
            FallbackTarget target = fallback.targets().get(k);
            List<String> callArguments = new ArrayList<>(arguments);
            if (target.exceptionType() == null || target.catchesAll()) {
                if (target.exceptionType() != null) {
                    callArguments.add("r4c$e");
                }
                lines.add("return " + recoveryStage(style, target.receiver() + "." + target.name() + "("
                        + String.join(", ", callArguments) + ")", target.returnsValue(), target.widens(), result) + ";");
                return lines;
            }
            callArguments.add("r4c$f" + k);
            lines.add("if (r4c$e instanceof " + target.exceptionType() + " r4c$f" + k + ") {");
            lines.add("    return " + recoveryStage(style, target.receiver() + "." + target.name() + "("
                    + String.join(", ", callArguments) + ")", target.returnsValue(), target.widens(), result) + ";");
            lines.add("}");
        }
        lines.add("throw r4c$e;");
        return lines;
    }

    /**
     * What an asynchronous fallback's {@code call} becomes in {@code r4c$recover}: a completed stage for a value, the
     * stage of a returned {@code Mono}, a returned stage (widened for {@code ? extends}), or, for a {@code Flux}
     * method, one more stream of the call (a value becomes a stream of that one element).
     */
    private static String recoveryStage(AsyncStyle style, String call, boolean returnsValue, boolean widens, String result) {
        if (style == AsyncStyle.FLUX) {
            return "r4c$stream." + (returnsValue ? "recoverValue(" : "recover(") + call + ")";
        }
        if (returnsValue) {
            return "java.util.concurrent.CompletableFuture.completedFuture(" + call + ")";
        }
        return style == AsyncStyle.MONO ? monoStage(call, result) : widens ? widen(call, result) : call;
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
            case RETRY -> retryLayer(model, index, call, isVoid, returnType, arguments);
            case HEDGE -> throw new IllegalStateException("@Hedge on a synchronous method is reported by checkHedge");
            case CIRCUIT_BREAKER -> classifierField(model, kind, index) == null
                    ? circuitBreaker(index, call, isVoid, returnType)
                    : classifiedCircuitBreaker(index, call, isVoid, returnType, model.policies().get(kind).classifier(),
                            classifierField(model, kind, index), breakerInsideRetry(model));
            case RATE_LIMITER -> rateLimiter(index, call, isVoid);
            case ADAPTIVE_LIMIT -> adaptiveLimit(index, call, isVoid, returnType);
            case TIMEOUT -> timeout(index, call, isVoid, returnType);
            case BULKHEAD -> bulkhead(index, call, isVoid);
        };
    }

    /** The retry layer, with the fallback fused into its give-up branches. */
    private static List<String> retryLayer(MethodModel model, int index, String call, boolean isVoid, String returnType,
            List<String> arguments) {
        String classifier = classifierField(model, Kind.RETRY, index);
        boolean classifiedBreaker = classifierField(model, Kind.CIRCUIT_BREAKER, index) != null && breakerInsideRetry(model);
        List<String> giveUp = giveUp(model, index, "r4c$e", "r4c$attempts", isVoid, arguments);
        List<String> onClassifierFailure =
                onClassifierFailure(giveUp(model, index, "r4c$c", "r4c$attempts + 1", isVoid, arguments));
        if (classifier == null) {
            return retry(index, call, isVoid, giveUp, classifiedBreaker ? onClassifierFailure : null);
        }
        PolicyModel retry = model.policies().get(Kind.RETRY);
        List<String> giveUpOnRejected;
        if (!retry.throwOnExhaustedResult()) {
            giveUpOnRejected = null;
        } else if (!fallbackFused(model)) {
            giveUpOnRejected = List.of();
        } else {
            giveUpOnRejected = giveUp(model, index, "r4c$r", "r4c$attempts", isVoid, arguments);
        }
        return classifiedRetry(index, call, isVoid, returnType, retry.classifier(), classifier, giveUp,
                onClassifierFailure, classifiedBreaker, giveUpOnRejected);
    }

    /**
     * A retry loop. {@code onClassifierFailure} is null, unless the breaker inside has a classifier: then the
     * statements that end the call with that classifier's exception, which the breaker throws wrapped in a
     * {@code ClassifierFailure} (see {@link #innerClassifierFailure}).
     */
    private static List<String> retry(int index, String call, boolean isVoid, List<String> giveUp,
            List<String> onClassifierFailure) {
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
        if (onClassifierFailure != null) {
            lines.addAll(innerClassifierFailure(onClassifierFailure));
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
        return !fallbackFused(model) ? List.of("throw " + failure + ";")
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
            return ownClassifierField(Kind.RETRY, index);
        }
        return ownClassifierField(kind, index);
    }

    /** True if {@code kind}'s layer has a classifier field of its own (one that it does not share). */
    private static boolean ownsClassifier(MethodModel model, Kind kind, int index) {
        String field = classifierField(model, kind, index);
        return field != null && field.equals(ownClassifierField(kind, index));
    }

    private static String ownClassifierField(Kind kind, int index) {
        return kind.field + "Classifier" + index;
    }

    private static final String OUTCOME = "dev.resilience4cata.Classifier.Outcome";

    /**
     * What the retry layer does when a classifier throws (its own, or the breaker's): publishes the give-up
     * and ends the call with the exception in {@code r4c$c}, through {@code giveUp} (the fallback, or a rethrow).
     */
    private static List<String> onClassifierFailure(List<String> giveUp) {
        List<String> lines = new ArrayList<>();
        lines.add("r4c$retry.onClassifierFailure(r4c$attempts + 1, r4c$c);");
        lines.addAll(giveUp);
        return lines;
    }

    /**
     * A catch clause of the retry's try, before the one for the call's failures: the breaker inside threw
     * its classifier's exception wrapped in a {@code ClassifierFailure}, which ends the call at once (it is
     * neither classified nor retried). {@code failure()} hands the exception back to a catch parameter, so
     * that {@code throw r4c$c} is a precise rethrow.
     */
    private static List<String> innerClassifierFailure(List<String> onClassifierFailure) {
        List<String> lines = new ArrayList<>();
        lines.add("    } catch (dev.resilience4cata.generated.ClassifierFailure r4c$w) {");
        lines.add("        try {");
        lines.add("            throw r4c$w.failure();");
        lines.add("        } catch (java.lang.Throwable r4c$c) {");
        for (String line : onClassifierFailure) {
            lines.add("            " + line);
        }
        lines.add("        }");
        return lines;
    }

    /**
     * A retry loop with a classifier. Exceptions get the classifier's verdict (abortOn still wins); a result
     * classified FAILURE is retried, and returned when the retries give up. If the classifier throws, the
     * loop publishes the give-up and gives up with that exception ({@code onClassifierFailure}, which reads it
     * from {@code r4c$c}); with {@code classifiedBreaker}, the same happens when the breaker's classifier throws.
     * {@code giveUpOnRejected} is null for {@code onExhaustedResult = RETURN}; for {@code THROW}, the fallback
     * statements that read the {@code ResultRejectedException} from {@code r4c$r}, or empty without a fallback.
     */
    @SuppressWarnings("java:S107") // java:S107: the parts of the layer that retryLayer prepares
    private static List<String> classifiedRetry(int index, String call, boolean isVoid, String returnType,
            String classifierType, String classifierField, List<String> giveUp, List<String> onClassifierFailure,
            boolean classifiedBreaker, List<String> giveUpOnRejected) {
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
        if (classifiedBreaker) {
            lines.addAll(innerClassifierFailure(onClassifierFailure));
        }
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
     * that a half-open probe permit is never lost, and the classifier's exception propagates: wrapped in a
     * {@code ClassifierFailure} if {@code insideRetry}, so that the retry ends the call with it instead of
     * classifying it again.
     */
    private static List<String> classifiedCircuitBreaker(int index, String call, boolean isVoid, String returnType,
            String classifierType, String classifierField, boolean insideRetry) {
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
                insideRetry ? "throw new dev.resilience4cata.generated.ClassifierFailure(r4c$c);" : "throw r4c$c;");
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

    private static List<String> adaptiveLimit(int index, String call, boolean isVoid, String returnType) {
        List<String> lines = new ArrayList<>();
        lines.add("final " + Kind.ADAPTIVE_LIMIT.policyType + " r4c$adaptiveLimit = this." + Kind.ADAPTIVE_LIMIT.field + index + ";");
        lines.add("final long r4c$start = r4c$adaptiveLimit.acquire();");
        if (!isVoid) {
            lines.add("final " + returnType + " r4c$result;");
        }
        lines.add("try {");
        lines.add("    " + (isVoid ? "" : "r4c$result = ") + call + ";");
        lines.add("} catch (java.lang.Throwable r4c$e) {");
        lines.add("    r4c$adaptiveLimit.onFailure(r4c$start, r4c$e);");
        lines.add("    throw r4c$e;");
        lines.add("}");
        lines.add("r4c$adaptiveLimit.onSuccess(r4c$start);");
        lines.add(isVoid ? "return;" : "return r4c$result;");
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
        if (bounds.isEmpty() || (bounds.size() == 1 && TypePrinter.print(bounds.get(0)).equals(OBJECT))) {
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
    /** {@code layers}: the policies' kinds, outermost first, in the default or the {@code @PolicyOrder} order. */
    private record MethodModel(ExecutableElement method, ExecutableType type, Map<Kind, PolicyModel> policies,
            FallbackModel fallback, TypeMirror asyncResult, List<Kind> layers) {
    }

    /**
     * A validated {@code @Fallback}. Exactly one of {@code handler} (qualified class name) and
     * {@code targets} (sibling methods, most specific exception first) is set; the other is null or empty.
     * {@code checkedThrown} lists the checked exceptions the method declares, which a handler may throw as is.
     * {@code handlerWidens}: for an async method, the handler returns a {@code CompletionStage<? extends T>}.
     * {@code taken}: the class literals of the siblings' exception types when none of them takes every
     * failure, passed to the registry so that the policy handles only failures a sibling takes; null otherwise.
     */
    private record FallbackModel(String instanceId, String applyOn, String skipOn, String handler,
            boolean handlerReturnsValue, List<FallbackTarget> targets, List<String> checkedThrown,
            boolean handlerWidens, String taken) {
    }

    /**
     * One sibling fallback method. {@code name} is what follows the receiver in the call: the method name,
     * preceded by explicit type arguments for a generic method ({@code <T>load}). {@code exceptionType} is
     * null when it takes no exception parameter, and {@code exceptionClass} is its class literal (erased,
     * {@code java.io.IOException.class}); {@code catchesAll} is true when it takes none or takes
     * {@code Throwable}. {@code returnsValue}: for an async method, it returns {@code T} rather than a stage;
     * {@code widens}: it returns a {@code CompletionStage<? extends T>}.
     */
    private record FallbackTarget(String receiver, String name, String exceptionType, String exceptionClass, boolean catchesAll,
            boolean returnsValue, boolean widens) {
    }

    /**
     * {@code classifier} is the qualified name of the classifier class, or null for none.
     * {@code throwOnExhaustedResult}: {@code @Retry(onExhaustedResult = THROW)}.
     * {@code locked}: {@code locked = true} with at least one attribute in {@code overrides}.
     */
    private record PolicyModel(String instanceId, String name, String config, List<String> overrides,
            String classifier, boolean throwOnExhaustedResult, boolean locked) {

        PolicyModel(String instanceId, String name, String config, List<String> overrides, String classifier) {
            this(instanceId, name, config, overrides, classifier, false, false);
        }
    }
}
