package dev.resilience4cata.quarkus.deployment;

import dev.resilience4cata.quarkus.runtime.MicrometerBinding;
import dev.resilience4cata.quarkus.runtime.OpenTelemetryBinding;
import dev.resilience4cata.quarkus.runtime.ResilienceBuildTimeConfig;
import dev.resilience4cata.quarkus.runtime.ResilienceHealthCheck;
import dev.resilience4cata.quarkus.runtime.ResilienceProducers;
import dev.resilience4cata.quarkus.runtime.ResilienceStartup;
import dev.resilience4cata.quarkus.runtime.ResilientWrapperCreator;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.AnnotationsTransformerBuildItem;
import io.quarkus.arc.deployment.BeanRegistrationPhaseBuildItem;
import io.quarkus.arc.deployment.BuildTimeConditionBuildItem;
import io.quarkus.arc.deployment.BeanRegistrationPhaseBuildItem.BeanConfiguratorBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.BeanConfigurator;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.metrics.MetricsCapabilityBuildItem;
import io.quarkus.runtime.metrics.MetricsFactory;
import jakarta.enterprise.inject.spi.DeploymentException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTransformation;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The build steps of the extension. Nothing here runs on the call path: the protection is the generated code's, and
 * the extension only makes the generated classes the beans the application gets.
 *
 * <ul>
 *   <li>Annotated class {@code C} (subclass style): {@code C} is vetoed and its generated {@code C$Resilient} becomes
 *       the bean in its place, with {@code C}'s class-level annotations (scope, qualifiers, stereotypes...); the
 *       mirrored constructors already carry {@code C}'s constructor annotations ({@code @Inject}), and the leading
 *       {@code ResilienceRegistry} parameter is injected.
 *   <li>Bean {@code B} implementing an annotated interface {@code I}: a synthetic bean of the interface's types, with
 *       {@code B}'s qualifiers, an enabled alternative with a high priority, created by
 *       {@code Resilient.of(I.class, b, registry)}. {@code @Inject I} gets the wrapper, {@code @Inject B} the bean
 *       itself.
 *   <li>REST client interface {@code I} ({@code @RegisterRestClient}) with a wrapper: a synthetic {@code @Default}
 *       bean around the {@code @RestClient} one.
 * </ul>
 */
public class ResilienceExtensionProcessor {

    private static final Logger LOG = Logger.getLogger(ResilienceExtensionProcessor.class);

    static final String FEATURE = "resilience4cata";
    static final String SUFFIX = "$Resilient";
    /** Above application alternatives, so that a wrapper wins over its own delegate; see {@link #wrapperPriority}. */
    static final int WRAPPER_PRIORITY = 1_000_000;

    private static final DotName REGISTRY = DotName.createSimple("dev.resilience4cata.ResilienceRegistry");
    private static final String POLICY_ANNOTATIONS = "dev.resilience4cata.annotation.";
    private static final DotName RESILIENCE_POLICY = DotName.createSimple(POLICY_ANNOTATIONS + "ResiliencePolicy");
    private static final List<String> FAULT_TOLERANCE = List.of("org.eclipse.microprofile.faulttolerance.",
            "io.smallrye.faulttolerance.api.");
    private static final DotName VETOED = DotName.createSimple("jakarta.enterprise.inject.Vetoed");
    private static final DotName INJECT = DotName.createSimple("jakarta.inject.Inject");
    private static final DotName PRODUCES = DotName.createSimple("jakarta.enterprise.inject.Produces");
    private static final DotName ANY = DotName.createSimple("jakarta.enterprise.inject.Any");
    private static final DotName REGISTER_REST_CLIENT =
            DotName.createSimple("org.eclipse.microprofile.rest.client.inject.RegisterRestClient");
    private static final DotName REST_CLIENT = DotName.createSimple("org.eclipse.microprofile.rest.client.inject.RestClient");
    private static final DotName TRANSACTIONAL = DotName.createSimple("jakarta.transaction.Transactional");
    private static final DotName OBJECT = DotName.createSimple(Object.class.getName());

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /** The registry, its startup, and the optional health check and metrics bindings. */
    @BuildStep
    void beans(ResilienceBuildTimeConfig config, Capabilities capabilities, Optional<MetricsCapabilityBuildItem> metrics,
            BuildProducer<AdditionalBeanBuildItem> beans) {
        beans.produce(AdditionalBeanBuildItem.builder().addBeanClasses(ResilienceProducers.class, ResilienceStartup.class)
                .setUnremovable().build());
        if (config.health().enabled() && capabilities.isPresent(Capability.SMALLRYE_HEALTH)) {
            beans.produce(AdditionalBeanBuildItem.unremovableOf(ResilienceHealthCheck.class));
        }
        if (!config.metrics().enabled()) {
            return;
        }
        if (metrics.isPresent() && metrics.get().metricsSupported(MetricsFactory.MICROMETER)
                && QuarkusClassLoader.isClassPresentAtRuntime("dev.resilience4cata.micrometer.MicrometerMetrics")) {
            beans.produce(AdditionalBeanBuildItem.unremovableOf(MicrometerBinding.class));
        } else if (capabilities.isCapabilityWithPrefixPresent("io.quarkus.opentelemetry")
                && QuarkusClassLoader.isClassPresentAtRuntime("dev.resilience4cata.opentelemetry.OpenTelemetryMetrics")) {
            beans.produce(AdditionalBeanBuildItem.unremovableOf(OpenTelemetryBinding.class));
        }
    }

    /** Finds the generated classes: {@code X$Resilient} implementing interface {@code X}, or extending class {@code X}. */
    @BuildStep
    GeneratedTypesBuildItem generatedTypes(CombinedIndexBuildItem combined) {
        IndexView index = combined.getIndex();
        Map<String, ClassInfo> wrappers = new HashMap<>();
        Map<String, ClassInfo> subclasses = new HashMap<>();
        for (ClassInfo generated : index.getKnownClasses()) {
            String name = generated.name().toString();
            if (!name.endsWith(SUFFIX) || generated.isInterface() || generated.isAnnotation()) {
                continue;
            }
            String annotatedName = name.substring(0, name.length() - SUFFIX.length());
            ClassInfo annotated = index.getClassByName(annotatedName);
            if (annotated == null) {
                continue;
            }
            if (annotated.isInterface()
                    && generated.interfaceNames().stream().anyMatch(i -> i.toString().equals(annotatedName))) {
                wrappers.put(annotatedName, generated);
            } else if (!annotated.isInterface() && generated.superName() != null
                    && generated.superName().toString().equals(annotatedName)) {
                subclasses.put(annotatedName, generated);
            }
        }
        return new GeneratedTypesBuildItem(wrappers, subclasses);
    }

    /**
     * The interface wrappers are never beans or endpoints themselves: their constructor takes the delegate. Without
     * this, Quarkus REST would serve the wrapper of a REST client interface (it implements an interface with
     * {@code @Path}), and ArC could discover it. A disabled build-time condition excludes a class from both.
     */
    @BuildStep
    void excludeWrappers(GeneratedTypesBuildItem generated, BuildProducer<BuildTimeConditionBuildItem> conditions) {
        generated.wrappers().values().forEach(wrapper -> conditions.produce(new BuildTimeConditionBuildItem(wrapper, false)));
    }

    /**
     * Subclass style: vetoes each annotated class and gives its generated subclass the class's own (not inherited)
     * class-level annotations, so the subclass is the bean, with the same scope, qualifiers and stereotypes. A
     * constructor injected without {@code @Inject} (the class's no-argument one, when it has others) gets it on its
     * mirror, which takes only the registry.
     */
    @BuildStep
    void replaceAnnotatedClasses(ResilienceBuildTimeConfig config, GeneratedTypesBuildItem generated,
            CombinedIndexBuildItem combined, BuildProducer<AnnotationsTransformerBuildItem> transformers) {
        if (!config.beans().enabled()) {
            return;
        }
        IndexView index = combined.getIndex();
        Set<String> excluded = Set.copyOf(config.beans().exclude().orElse(List.of()));
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, ClassInfo> entry : generated.subclasses().entrySet()) {
            ClassInfo annotated = index.getClassByName(entry.getKey());
            ClassInfo subclass = entry.getValue();
            if (excluded.contains(entry.getKey()) || annotated == null) {
                continue;
            }
            List<String> producers = producers(annotated);
            if (!producers.isEmpty()) {
                problems.add(entry.getKey() + " declares " + String.join(", ", producers));
            }
            List<AnnotationInstance> copied = new ArrayList<>();
            for (AnnotationInstance annotation : annotated.declaredAnnotations()) {
                // @Inherited ones too: ArC discovers a bean by its own bean-defining annotations, not inherited ones.
                if (!isPolicyAnnotation(annotation.name(), index) && !annotation.name().equals(VETOED)
                        && !subclass.hasDeclaredAnnotation(annotation.name())) {
                    copied.add(AnnotationInstance.create(annotation.name(), subclass, annotation.values()));
                }
            }
            DotName annotatedName = annotated.name();
            DotName subclassName = subclass.name();
            transformers.produce(new AnnotationsTransformerBuildItem(AnnotationTransformation.forClasses()
                    .when(context -> context.declaration().asClass().name().equals(annotatedName))
                    .transform(context -> context.add(AnnotationInstance.create(VETOED, annotated, List.of())))));
            if (!copied.isEmpty()) {
                transformers.produce(new AnnotationsTransformerBuildItem(AnnotationTransformation.forClasses()
                        .when(context -> context.declaration().asClass().name().equals(subclassName))
                        .transform(context -> context.addAll(copied))));
            }
            MethodInfo implicit = implicitlyInjected(subclass);
            if (implicit != null) {
                transformers.produce(new AnnotationsTransformerBuildItem(AnnotationTransformation.forMethods()
                        .when(context -> context.declaration().asMethod().equals(implicit))
                        .transform(context -> context.add(AnnotationInstance.create(INJECT, implicit, List.of())))));
            }
        }
        if (!problems.isEmpty()) {
            throw new DeploymentException(ErrorCode.PRODUCER_ON_ANNOTATED_CLASS.message("Annotated classes declare"
                    + " producers: " + String.join("; ", problems) + ". The generated subclass replaces the class as the"
                    + " bean, and CDI producers are not inherited, so these would no longer exist. Move the producers to"
                    + " another bean, or exclude the class with quarkus.resilience4cata.beans.exclude."));
        }
    }

    /** Producer methods and fields declared by {@code type}. */
    private static List<String> producers(ClassInfo type) {
        List<String> producers = new ArrayList<>();
        type.methods().stream().filter(m -> m.hasDeclaredAnnotation(PRODUCES)).forEach(m -> producers.add(m.name() + "()"));
        type.fields().stream().filter(f -> f.hasDeclaredAnnotation(PRODUCES)).forEach(f -> producers.add(f.name()));
        return producers;
    }

    /**
     * The constructor of the subclass that CDI would use without {@code @Inject}: the only one (Quarkus injects a sole
     * constructor, but decides it from the class's original annotations, before the subclass got its scope), or,
     * with several and none {@code @Inject}, the mirror of the class's no-argument one, which takes only the
     * registry. {@code null} when one already has {@code @Inject}.
     */
    private static @Nullable MethodInfo implicitlyInjected(ClassInfo subclass) {
        List<MethodInfo> constructors = subclass.constructors();
        if (constructors.stream().anyMatch(c -> c.hasDeclaredAnnotation(INJECT))) {
            return null;
        }
        if (constructors.size() == 1) {
            return constructors.get(0);
        }
        return constructors.stream()
                .filter(c -> c.parametersCount() == 1 && c.parameterType(0).name().equals(REGISTRY))
                .findFirst().orElse(null);
    }

    /**
     * Interface style: one synthetic wrapper bean per class bean that implements an annotated interface, and one per
     * REST client interface with a wrapper.
     */
    @BuildStep
    void wrapInterfaces(ResilienceBuildTimeConfig config, BeanRegistrationPhaseBuildItem phase,
            GeneratedTypesBuildItem generated, CombinedIndexBuildItem combined,
            BuildProducer<BeanConfiguratorBuildItem> configurators) {
        if (!config.beans().enabled() || generated.wrappers().isEmpty()) {
            return;
        }
        IndexView index = combined.getIndex();
        Set<String> excluded = Set.copyOf(config.beans().exclude().orElse(List.of()));
        List<String> problems = new ArrayList<>();
        for (BeanInfo bean : phase.getContext().beans()) {
            if (!bean.isClassBean() || bean.isInterceptor() || bean.isDecorator()) {
                continue;
            }
            String beanClass = bean.getBeanClass().toString();
            // $$: classes generated by frameworks, such as the REST client's StockClient$$CDIWrapper.
            if (excluded.contains(beanClass) || beanClass.contains("$$") || generated.isGenerated(beanClass)
                    || generated.subclasses().containsKey(beanClass)
                    || (bean.isAlternative() && bean.getPriority() == null)) {
                continue;
            }
            List<ClassInfo> annotated = mostSpecific(bean.getTypes(), generated, index);
            if (annotated.isEmpty()) {
                continue;
            }
            if (annotated.size() > 1) {
                problems.add(beanClass + " implements " + annotated.stream().map(c -> c.name().toString()).toList());
                continue;
            }
            ClassInfo type = annotated.get(0);
            List<AnnotationInstance> qualifiers = bean.getQualifiers().stream().filter(q -> !q.name().equals(ANY)).toList();
            boolean dependent = BuiltinScope.DEPENDENT.is(bean.getScope());
            BeanConfigurator<Object> wrapper = phase.getContext().configure(generated.wrappers().get(type.name().toString()).name());
            wrapper.identifier("resilience4cata-wrapper-" + bean.getIdentifier())
                    .addTypeClosure(type.name())
                    .scope(dependent ? BuiltinScope.DEPENDENT.getInfo() : BuiltinScope.SINGLETON.getInfo())
                    .alternative(true)
                    .priority(wrapperPriority(bean))
                    .unremovable()
                    .creator(ResilientWrapperCreator.class)
                    .param(ResilientWrapperCreator.INTERFACE, type)
                    .param(ResilientWrapperCreator.DELEGATE, bean.getImplClazz())
                    .param(ResilientWrapperCreator.QUALIFIERS, qualifiers.toArray(AnnotationInstance[]::new))
                    .addInjectionPoint(ClassType.create(bean.getBeanClass()), qualifiers.toArray(AnnotationInstance[]::new))
                    .addInjectionPoint(ClassType.create(REGISTRY));
            qualifiers.forEach(wrapper::addQualifier);
            if (bean.getScope().isNormal()) {
                wrapper.startup(); // cheap: it holds the bean's client proxy; creates the policies at startup
            }
            configurators.produce(new BeanConfiguratorBuildItem(wrapper));
            LOG.debugf("resilience4cata: %s is injected as %s through %s", beanClass, type.name(), SUFFIX);
        }
        for (Map.Entry<String, ClassInfo> entry : generated.wrappers().entrySet()) {
            ClassInfo type = index.getClassByName(entry.getKey());
            if (type == null || !type.hasDeclaredAnnotation(REGISTER_REST_CLIENT) || excluded.contains(entry.getKey())) {
                continue;
            }
            AnnotationInstance restClient = AnnotationInstance.create(REST_CLIENT, null, List.of());
            BeanConfigurator<Object> wrapper = phase.getContext().configure(entry.getValue().name());
            wrapper.identifier("resilience4cata-rest-client-wrapper-" + entry.getKey())
                    .addTypeClosure(type.name())
                    .scope(BuiltinScope.SINGLETON.getInfo())
                    .unremovable()
                    .creator(ResilientWrapperCreator.class)
                    .param(ResilientWrapperCreator.INTERFACE, type)
                    .param(ResilientWrapperCreator.DELEGATE, type)
                    .param(ResilientWrapperCreator.QUALIFIERS, new AnnotationInstance[] {restClient})
                    .addInjectionPoint(ClassType.create(type.name()), restClient)
                    .addInjectionPoint(ClassType.create(REGISTRY));
            configurators.produce(new BeanConfiguratorBuildItem(wrapper));
        }
        if (!problems.isEmpty()) {
            throw new DeploymentException(ErrorCode.SEVERAL_INTERFACES.message("Beans implement several annotated"
                    + " interfaces that do not extend one another: " + String.join("; ", problems) + ". A bean can be"
                    + " injected through one generated wrapper only. Declare the policies on one interface that extends"
                    + " the others, or exclude the bean with quarkus.resilience4cata.beans.exclude and wrap it yourself"
                    + " with Resilient.of in a producer method."));
        }
    }

    /**
     * The wrapper of a bean that is itself an enabled alternative must outrank the wrappers of the beans it replaces,
     * as the bean outranks them.
     */
    static int wrapperPriority(BeanInfo bean) {
        Integer priority = bean.isAlternative() ? bean.getPriority() : null;
        return priority == null ? WRAPPER_PRIORITY : WRAPPER_PRIORITY + 1 + Math.max(0, priority);
    }

    /** The annotated interfaces among {@code types} that no other of them extends. */
    private static List<ClassInfo> mostSpecific(Set<Type> types, GeneratedTypesBuildItem generated, IndexView index) {
        List<ClassInfo> annotated = new ArrayList<>();
        for (Type type : types) {
            ClassInfo info = generated.wrappers().containsKey(type.name().toString()) ? index.getClassByName(type.name()) : null;
            if (info != null) {
                annotated.add(info);
            }
        }
        List<ClassInfo> specific = new ArrayList<>();
        for (ClassInfo candidate : annotated) {
            boolean extended = annotated.stream().anyMatch(other -> other != candidate
                    && superInterfaces(other, index).contains(candidate.name().toString()));
            if (!extended) {
                specific.add(candidate);
            }
        }
        return specific;
    }

    private static Set<String> superInterfaces(ClassInfo type, IndexView index) {
        Set<String> all = new LinkedHashSet<>();
        List<DotName> pending = new ArrayList<>(type.interfaceNames());
        while (!pending.isEmpty()) {
            DotName name = pending.removeLast();
            if (all.add(name.toString())) {
                ClassInfo info = index.getClassByName(name);
                if (info != null) {
                    pending.addAll(info.interfaceNames());
                }
            }
        }
        return all;
    }

    /**
     * R4C-QK-001: a method protected by resilience4cata that also carries MicroProfile/SmallRye Fault Tolerance
     * annotations (on the method or its class) would get both. An error when SmallRye Fault Tolerance is present,
     * a warning otherwise (its annotations then do nothing).
     */
    @BuildStep
    void checkFaultTolerance(Capabilities capabilities, GeneratedTypesBuildItem generated, CombinedIndexBuildItem combined,
            BuildProducer<ValidationErrorBuildItem> errors) {
        IndexView index = combined.getIndex();
        Set<String> clashes = new TreeSet<>();
        for (Map.Entry<String, ClassInfo> entry : generated.subclasses().entrySet()) {
            ClassInfo annotated = index.getClassByName(entry.getKey());
            if (annotated == null) {
                continue;
            }
            for (MethodInfo override : entry.getValue().methods()) {
                if (override.isConstructor() || override.isSynthetic() || override.isStaticInitializer()
                        || java.lang.reflect.Modifier.isPrivate(override.flags())) {
                    continue;
                }
                MethodInfo declared = declaredIn(annotated, override, index);
                if (faultTolerance(override.declaredAnnotations()) || (declared != null
                        && (faultTolerance(declared.declaredAnnotations())
                        || faultTolerance(declared.declaringClass().declaredAnnotations())))) {
                    clashes.add(entry.getKey() + "." + override.name() + "()");
                }
            }
        }
        for (String name : generated.wrappers().keySet()) {
            ClassInfo type = index.getClassByName(name);
            if (type == null) {
                continue;
            }
            for (MethodInfo method : protectedMethods(type, index)) {
                if (faultTolerance(method.declaredAnnotations()) || faultTolerance(type.declaredAnnotations())) {
                    clashes.add(name + "." + method.name() + "()");
                }
                for (ClassInfo implementation : index.getAllKnownImplementations(type.name())) {
                    MethodInfo implemented = declaredIn(implementation, method, index);
                    if (implemented != null && (faultTolerance(implemented.declaredAnnotations())
                            || faultTolerance(implemented.declaringClass().declaredAnnotations()))) {
                        clashes.add(implemented.declaringClass().name() + "." + method.name() + "() (implements " + name + ")");
                    }
                }
            }
        }
        if (clashes.isEmpty()) {
            return;
        }
        boolean active = capabilities.isPresent(Capability.SMALLRYE_FAULT_TOLERANCE);
        String message = ErrorCode.FAULT_TOLERANCE_CLASH.message("Methods protected by resilience4cata also carry"
                + " MicroProfile Fault Tolerance annotations: " + String.join(", ", clashes) + ". "
                + (active ? "SmallRye Fault Tolerance would apply its interceptor as well, so both libraries would retry,"
                        + " time out and count the same calls."
                        : "SmallRye Fault Tolerance is not installed, so those annotations do nothing.")
                + " The simple names are the same (@Retry, @CircuitBreaker, @Timeout, @Bulkhead, @Fallback): check the"
                + " imports, and keep one library per method (see the Quarkus guide, \"SmallRye Fault Tolerance vs"
                + " resilience4cata\").");
        if (active) {
            errors.produce(new ValidationErrorBuildItem(new DeploymentException(message)));
        } else {
            LOG.warn(message);
        }
    }

    /** The methods of an annotated interface that carry a policy, or all of them with a type-level policy. */
    private static List<MethodInfo> protectedMethods(ClassInfo type, IndexView index) {
        List<MethodInfo> methods = new ArrayList<>();
        List<ClassInfo> hierarchy = new ArrayList<>();
        hierarchy.add(type);
        for (String name : superInterfaces(type, index)) {
            ClassInfo info = index.getClassByName(name);
            if (info != null) {
                hierarchy.add(info);
            }
        }
        boolean typeLevel = hierarchy.stream().anyMatch(t -> policy(t.declaredAnnotations(), index));
        for (ClassInfo declaring : hierarchy) {
            for (MethodInfo method : declaring.methods()) {
                if (!java.lang.reflect.Modifier.isStatic(method.flags())
                        && (typeLevel || policy(method.declaredAnnotations(), index))) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    /** The method with {@code method}'s name and parameters declared by {@code type} or its nearest superclass. */
    private static @Nullable MethodInfo declaredIn(ClassInfo type, MethodInfo method, IndexView index) {
        ClassInfo current = type;
        while (current != null && !current.name().equals(OBJECT)) {
            MethodInfo found = current.method(method.name(), method.parameterTypes());
            if (found != null && found != method) {
                return found;
            }
            current = current.superName() == null ? null : index.getClassByName(current.superName());
        }
        return null;
    }

    private static boolean faultTolerance(Iterable<AnnotationInstance> annotations) {
        for (AnnotationInstance annotation : annotations) {
            String name = annotation.name().toString();
            if (FAULT_TOLERANCE.stream().anyMatch(name::startsWith)) {
                return true;
            }
        }
        return false;
    }

    private static boolean policy(Iterable<AnnotationInstance> annotations, IndexView index) {
        for (AnnotationInstance annotation : annotations) {
            if (isPolicyAnnotation(annotation.name(), index)) {
                return true;
            }
        }
        return false;
    }

    /** A resilience4cata annotation, or a bundle (an annotation meta-annotated with {@code @ResiliencePolicy}). */
    private static boolean isPolicyAnnotation(DotName name, IndexView index) {
        if (name.toString().startsWith(POLICY_ANNOTATIONS)) {
            return true;
        }
        ClassInfo annotation = index.getClassByName(name);
        return annotation != null && annotation.hasDeclaredAnnotation(RESILIENCE_POLICY);
    }

    /**
     * R4C-QK-002: a {@code @Transactional} interceptor goes around a generated subclass whose retried or hedged method
     * joins or starts a transaction, so every attempt would run in one transaction. Read from ArC's own interceptor
     * bindings of the bean, and from the generated class's bytecode for which methods retry or hedge.
     */
    @BuildStep
    void checkTransactions(ResilienceBuildTimeConfig config, ValidationPhaseBuildItem validation,
            GeneratedTypesBuildItem generated, BuildProducer<ValidationErrorBuildItem> errors) {
        if (config.allowRetryInsideTransaction() || generated.subclasses().isEmpty()) {
            return;
        }
        checkTransactions(validation, generated, errors);
    }

    /**
     * R4C-QK-005: a normal-scoped generated subclass needs a no-argument constructor for its client proxy, which ArC
     * can add only when the annotated class has one; ArC's own message would name the generated class, which the
     * application cannot change.
     */
    @BuildStep
    void checkProxyable(ValidationPhaseBuildItem validation, GeneratedTypesBuildItem generated,
            CombinedIndexBuildItem combined, BuildProducer<ValidationErrorBuildItem> errors) {
        Map<String, String> annotatedBySubclass = new HashMap<>();
        generated.subclasses().forEach((annotated, subclass) -> annotatedBySubclass.put(subclass.name().toString(), annotated));
        for (BeanInfo bean : allBeans(validation)) {
            String annotated = bean.isClassBean() ? annotatedBySubclass.get(bean.getBeanClass().toString()) : null;
            ClassInfo type = annotated == null ? null : combined.getIndex().getClassByName(annotated);
            if (type == null || !bean.getScope().isNormal()) {
                continue;
            }
            MethodInfo noArgs = type.method("<init>");
            if (noArgs == null || java.lang.reflect.Modifier.isPrivate(noArgs.flags())) {
                errors.produce(new ValidationErrorBuildItem(new DeploymentException(
                        ErrorCode.UNPROXYABLE_ANNOTATED_CLASS.message(annotated + " is a normal-scoped bean (@"
                        + bean.getScope().getDotName().withoutPackagePrefix() + ") with resilience4cata annotations, but"
                        + " has no non-private constructor without parameters. Its generated subclass " + annotated
                        + SUFFIX + " is the bean, and the client proxy that Quarkus puts in front of a normal-scoped"
                        + " bean needs such a constructor, which Quarkus can add to the subclass only if " + annotated
                        + " has one. Add one (package-private is enough; only the proxy uses it), or use field"
                        + " injection instead of constructor injection, or make the bean @Singleton."))));
            }
        }
    }

    private void checkTransactions(ValidationPhaseBuildItem validation, GeneratedTypesBuildItem generated,
            BuildProducer<ValidationErrorBuildItem> errors) {
        Map<String, String> annotatedBySubclass = new HashMap<>();
        generated.subclasses().forEach((annotated, subclass) -> annotatedBySubclass.put(subclass.name().toString(), annotated));
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        List<Throwable> problems = new ArrayList<>();
        for (BeanInfo bean : allBeans(validation)) {
            String annotated = bean.isClassBean() ? annotatedBySubclass.get(bean.getBeanClass().toString()) : null;
            if (annotated == null) {
                continue;
            }
            Map<String, String> repeating = RepeatingMethods.of(loader, bean.getBeanClass().toString());
            Set<String> found = new TreeSet<>();
            for (Map.Entry<MethodInfo, Set<AnnotationInstance>> method : bean.getInterceptedMethodsBindings().entrySet()) {
                String policy = repeating.get(method.getKey().name() + method.getKey().descriptor());
                if (policy == null) {
                    continue;
                }
                for (AnnotationInstance binding : method.getValue()) {
                    String type = binding.name().equals(TRANSACTIONAL) ? txType(binding) : null;
                    if (type != null && !type.equals("NOT_SUPPORTED") && !type.equals("NEVER")) {
                        found.add(policy + " on " + method.getKey().name() + " (" + type + ")");
                    }
                }
            }
            if (!found.isEmpty()) {
                problems.add(new DeploymentException(ErrorCode.RETRY_INSIDE_TRANSACTION.message("Bean " + annotated
                        + " has transactional methods that are retried or hedged: " + String.join(", ", found)
                        + ". The transaction interceptor goes around the generated " + annotated + SUFFIX + ", so every"
                        + " attempt would run in the same transaction, and an attempt that fails and marks it"
                        + " rollback-only makes the commit of a later successful attempt fail. Declare the policy on an"
                        + " interface the bean implements (the wrapper then goes outside the transaction interceptor:"
                        + " one transaction per attempt), or move @Transactional to another bean that the retried"
                        + " method calls, or set quarkus.resilience4cata.allow-retry-inside-transaction=true if one"
                        + " shared transaction is intended.")));
            }
        }
        if (!problems.isEmpty()) {
            errors.produce(new ValidationErrorBuildItem(problems));
        }
    }

    /** The beans, and those ArC removed as unused: an unused bean is still a mistake once something injects it. */
    private static List<BeanInfo> allBeans(ValidationPhaseBuildItem validation) {
        List<BeanInfo> beans = new ArrayList<>();
        validation.getContext().beans().forEach(beans::add);
        validation.getContext().removedBeans().forEach(beans::add);
        return beans;
    }

    private static String txType(AnnotationInstance transactional) {
        AnnotationValue value = transactional.value();
        return value == null ? "REQUIRED" : value.asEnum();
    }
}
