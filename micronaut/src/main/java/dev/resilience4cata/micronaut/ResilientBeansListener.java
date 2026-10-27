package dev.resilience4cata.micronaut;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.inject.AdvisedBeanType;
import io.micronaut.inject.BeanDefinition;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replaces each bean annotated with {@link ResilientBean} by the generated wrapper of its interface, and stops
 * a bean that is an instance of an annotated class but not of its generated subclass, whose policies would never
 * run ({@code R4C-MN-004}), or a transaction proxy around retried methods of a generated subclass
 * ({@code R4C-MN-005}, {@link RetryInsideTransactionCheck}).
 *
 * <p>Ordered last, so the wrapper goes around the bean as every other listener left it; Micronaut AOP proxies
 * (an {@code $Intercepted} subclass) are the bean itself here, so their interceptors run inside the policies.
 * The registry is looked up when the first bean is wrapped, never when this listener is created. Runs once per
 * bean creation, never on the call path; the generated class of each bean class is looked up once.
 */
@Singleton
@Order(Ordered.LOWEST_PRECEDENCE)
final class ResilientBeansListener implements BeanCreatedEventListener<Object> {

    /** Scopes whose beans Micronaut proxies, so the bean it creates is a target the proxy calls by its class. */
    private static final String SCOPED_PROXY = "io.micronaut.runtime.context.scope.ScopedProxy";

    private static final Logger LOG = LoggerFactory.getLogger(ResilientBeansListener.class);

    private final BeanProvider<ResilienceRegistry> registry;
    private final Set<String> excluded;
    /** Micronaut Test's replacement of a bean in a test: a stub subclass of an annotated class is meant to be bare. */
    private static final String MOCK_BEAN = "io.micronaut.test.annotation.MockBean";
    private static final String CONTAINER_BEAN = "dev.resilience4cata.generated.ContainerBean";

    private final GeneratedClasses generated = new GeneratedClasses();
    private final Map<Class<?>, Optional<Class<?>>> unprotected = new ConcurrentHashMap<>();
    private final @Nullable RetryInsideTransactionCheck transactions;

    /**
     * A listener of every bean cannot depend on other beans (it would be notified of their creation): a provider
     * and a property only.
     */
    ResilientBeansListener(BeanProvider<ResilienceRegistry> registry,
            @Property(name = MicronautSettings.PREFIX + ".exclude") @Nullable List<String> excluded,
            @Property(name = MicronautSettings.PREFIX + ".allow-retry-inside-transaction") @Nullable Boolean allowRetryInsideTransaction) {
        this.registry = registry;
        this.excluded = excluded == null ? Set.of() : excluded.stream().map(String::strip).collect(Collectors.toUnmodifiableSet());
        this.transactions = Boolean.TRUE.equals(allowRetryInsideTransaction) ? null : new RetryInsideTransactionCheck(generated);
    }

    @Override
    public Object onCreated(BeanCreatedEvent<Object> event) {
        BeanDefinition<Object> definition = event.getBeanDefinition();
        Object bean = event.getBean();
        if (!excluded.isEmpty() && (excluded.contains(definition.getBeanType().getName())
                || excluded.contains(bean.getClass().getName()))) {
            return bean;
        }
        Class<?>[] declared = definition.classValues(ResilientBean.class);
        if (declared.length > 0) {
            // a generated subclass already applies its interfaces' policies: wrapping it would apply them twice
            return generated.isGeneratedSubclass(bean.getClass()) ? bean : wrap(declared[0], definition, bean);
        }
        Class<?> defined = definition instanceof AdvisedBeanType<?> advised ? advised.getInterceptedType()
                : definition.getBeanType();
        if (generated.isGeneratedSubclass(defined) && !definition.hasAnnotation(CONTAINER_BEAN)) {
            throw withoutInjectOption(definition, defined.getSuperclass());
        }
        Optional<Class<?>> annotated = unprotected.computeIfAbsent(bean.getClass(), this::annotatedSuperclass);
        if (annotated.isPresent() && !definition.hasStereotype(MOCK_BEAN)) {
            throw unprotected(definition, bean, annotated.get());
        }
        if (transactions != null) {
            transactions.check(definition, bean);
        }
        return bean;
    }

    @SuppressWarnings("unchecked")
    private <T> Object wrap(Class<T> type, BeanDefinition<Object> definition, Object bean) {
        validate(definition, type);
        String name = definition.getBeanType().getName();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Bean {} is wrapped with {}{}", name, type.getName(), Resilient.GENERATED_SUFFIX);
        }
        return Resilient.of(type, (T) bean, registry.get());
    }

    /**
     * Checks a {@link ResilientBean} definition: {@code type} is an interface the bean implements (R4C-MN-002), the
     * scope is not proxied (R4C-MN-003), and the bean is exposed as {@code type} only (R4C-MN-001). Also run on every
     * such definition at startup ({@link ResilientBeanDefinitions}), so a mistake fails fast.
     */
    static void validate(BeanDefinition<?> definition, Class<?> type) {
        String name = definition.getBeanType().getName();
        if (!type.isInterface() || !type.isAssignableFrom(definition.getBeanType())) {
            throw ErrorCode.NOT_IMPLEMENTED.exception("Bean " + name + " is annotated @ResilientBean(" + type.getName()
                    + ".class), but " + (type.isInterface() ? "it does not implement " + type.getSimpleName()
                    : type.getSimpleName() + " is not an interface") + ", so no generated wrapper can protect it.",
                    "Name the annotated interface that the bean implements; for an annotated class, remove"
                    + " @ResilientBean and compile with -Ar4c.inject=micronaut or create its generated subclass in a"
                    + " @Factory (see the Micronaut guide).");
        }
        if (definition.hasStereotype(SCOPED_PROXY)) {
            throw ErrorCode.PROXIED_SCOPE.exception("Bean " + name + " is annotated @ResilientBean but has a scope that"
                    + " Micronaut proxies (" + definition.getScopeName().orElse("@ScopedProxy") + "): the proxy looks up"
                    + " its target by the bean's class, which the generated wrapper is not.", "Use @Singleton,"
                    + " @Prototype or @Context, or inject the scoped bean into a singleton that implements the annotated"
                    + " interface.");
        }
        Set<Class<?>> exposed = definition.getExposedTypes();
        if (!exposed.equals(Set.of(type))) {
            throw ErrorCode.EXPOSED_TYPES.exception("Bean " + name + " is annotated @ResilientBean(" + type.getSimpleName()
                    + ".class) but is exposed as " + (exposed.isEmpty() ? "every type it has" : exposed.stream()
                    .map(Class::getName).sorted().toList()) + ". The generated wrapper implements " + type.getName()
                    + " only, so injecting the bean by another type would fail or bypass the policies.",
                    "Remove @Bean(typed = ...) or @Type from the bean, and inject " + type.getSimpleName() + ".");
        }
    }

    /** The annotated class among the bean's class and superclasses, unless the bean is its generated subclass. */
    private Optional<Class<?>> annotatedSuperclass(Class<?> beanClass) {
        for (Class<?> c = beanClass; c != null && c != Object.class; c = c.getSuperclass()) {
            if (generated.isGeneratedSubclass(c)) {
                return Optional.empty();
            }
            if (generated.subclassOf(c) != null) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /**
     * R4C-MN-004 for a generated subclass that Micronaut creates from its own definition, but which was compiled
     * without {@code -Ar4c.inject=micronaut}: a mirrored {@code @Inject} constructor made it a bean, without the
     * class's scope and qualifiers.
     */
    private static ResilienceBeanException withoutInjectOption(BeanDefinition<Object> definition, Class<?> annotated) {
        String subclass = annotated.getSimpleName() + Resilient.GENERATED_SUFFIX;
        return ErrorCode.UNPROTECTED_INSTANCE.exception("Bean " + definition.getBeanType().getName() + " is the generated "
                + subclass + ", compiled without the compiler option -A" + "r4c.inject=micronaut: Micronaut created it"
                + " only because its constructor repeats @Inject, so it does not have " + annotated.getSimpleName()
                + "'s scope and qualifiers.", "Add -Ar4c.inject=micronaut to the compiler options of the module that"
                + " declares " + annotated.getSimpleName() + " (see the Micronaut guide), or remove @Inject from its"
                + " constructor and create it from a @Factory method.");
    }

    private static ResilienceBeanException unprotected(BeanDefinition<Object> definition, Object bean, Class<?> annotated) {
        String subclass = annotated.getSimpleName() + Resilient.GENERATED_SUFFIX;
        String what = bean.getClass() == annotated ? "an instance of " + annotated.getName()
                : "an instance of " + bean.getClass().getName() + ", which extends " + annotated.getName();
        return ErrorCode.UNPROTECTED_INSTANCE.exception("Bean " + definition.getBeanType().getName() + " is " + what
                + ", which has resilience4cata annotations, but not of the generated " + subclass + ", so its"
                + " policies would not run. Micronaut created it itself (the class has a scope annotation or an"
                + " @Inject constructor) or a @Factory returned it.", "Remove the scope annotation (and @Inject)"
                + " from " + annotated.getSimpleName() + " and return new " + subclass + "(registry, ...) from a"
                + " @Factory method typed " + annotated.getSimpleName() + " (see the Micronaut guide), or list "
                + annotated.getName() + " in " + MicronautSettings.PREFIX + ".exclude to leave it unprotected.");
    }
}
