package dev.resilience4cata.spring.autoconfigure;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.aop.support.AopUtils;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ClassUtils;

/**
 * Applies the generated wrapper to beans that implement an annotated interface, and stops the startup
 * when a bean is an annotated class that bypassed {@link ResilientClassesPostProcessor}.
 *
 * <p>Not ordered, so it runs after every ordered post-processor, Spring's auto-proxy creator included: an
 * annotated interface's wrapper ({@code Resilient.of(I, bean, registry)}) goes around the bean as the
 * other post-processors left it, a {@code @Transactional} proxy for example, so each retry runs a new
 * transaction. The bean's type becomes the wrapper, which implements only {@code I}: inject {@code I}.
 * Beans that already are generated classes are left alone, so a {@code @Bean} method may return
 * {@code Resilient.of(...)} itself.
 *
 * <p>The registry is looked up when the first bean is wrapped, never when this post-processor is
 * created, so that it and its dependencies go through every post-processor.
 *
 * <p>A bean that another post-processor depends on is created while the post-processors are registered, before
 * this one is, so it never gets here. Once every singleton exists, the ones that should have been processed are
 * checked, and startup fails with R4C-SPR-005 for a bean that should have been wrapped.
 */
final class ResilientBeansPostProcessor implements SmartInstantiationAwareBeanPostProcessor,
        MergedBeanDefinitionPostProcessor, BeanFactoryAware, SmartInitializingSingleton {

    private static final String JAKARTA_INJECT = "jakarta.inject.Inject";

    private static final Log LOG = LogFactory.getLog(ResilientBeansPostProcessor.class);

    private final Supplier<ResilienceRegistry> registry;
    private final boolean wrap;
    private final Set<String> excluded;
    private final GeneratedClasses generated = new GeneratedClasses();
    private final Map<Class<?>, Plan> plans = new ConcurrentHashMap<>();
    private final Map<String, Object> earlyReferences = new ConcurrentHashMap<>();
    private @Nullable ConfigurableListableBeanFactory beanFactory;

    ResilientBeansPostProcessor(Supplier<ResilienceRegistry> registry, boolean wrap, Set<String> excluded) {
        this.registry = registry;
        this.wrap = wrap;
        this.excluded = Set.copyOf(excluded);
    }

    /** What to do with the beans of one class. */
    private sealed interface Plan {
    }

    private record Keep() implements Plan {
    }

    private record Wrap(Class<?> type) implements Plan {
    }

    private record Unprotected(Class<?> annotated, Class<?> subclass) implements Plan {
    }

    private record Several(List<Class<?>> interfaces) implements Plan {
    }

    private static final Keep KEEP = new Keep();

    /**
     * The constructor of a generated subclass that Spring would pick on the annotated class itself. With several
     * constructors and none {@code @Autowired} or {@code @Inject}, Spring uses the class's no-argument one; the
     * subclass mirrors it as the constructor that takes only the registry, which Spring would not find on its own.
     */
    @Override
    public Constructor<?> @Nullable [] determineCandidateConstructors(Class<?> beanClass, String beanName) {
        if (!generated.isGenerated(beanClass) || beanClass.getSuperclass() == null
                || generated.subclassOf(beanClass.getSuperclass()) != beanClass) {
            return null;
        }
        Constructor<?>[] constructors = beanClass.getDeclaredConstructors();
        if (constructors.length < 2 || Arrays.stream(constructors).anyMatch(ResilientBeansPostProcessor::injected)) {
            return null;
        }
        return Arrays.stream(constructors)
                .filter(c -> c.getParameterCount() == 1 && c.getParameterTypes()[0] == ResilienceRegistry.class)
                .findFirst().map(c -> new Constructor<?>[] {c}).orElse(null);
    }

    private static boolean injected(Constructor<?> constructor) {
        MergedAnnotations annotations = MergedAnnotations.from(constructor);
        return annotations.isPresent(Autowired.class) || annotations.isPresent(JAKARTA_INJECT);
    }

    /**
     * Gives a bean that will be wrapped the generic interface type as its target type ({@code Lookup<String>} for a
     * {@code NameLookup implements Lookup<String>}): Spring matches generic injection points against the target type
     * first, and once the bean is the wrapper its own class says only {@code Lookup}. Runs before the bean is created;
     * beans of non-generic interfaces are left as they are.
     */
    @Override
    public void postProcessMergedBeanDefinition(RootBeanDefinition definition, Class<?> beanType, String beanName) {
        if (!wrap || excluded.contains(beanName) || ScopedProxyUtils.isScopedTarget(beanName)) {
            return;
        }
        if (plans.computeIfAbsent(beanType, type -> plan(type, type)) instanceof Wrap(Class<?> type)) {
            ResolvableType interfaceType = ResolvableType.forClass(beanType).as(type);
            if (interfaceType.hasGenerics() && !interfaceType.hasUnresolvableGenerics()) {
                definition.setTargetType(interfaceType);
            }
        }
    }

    @Override
    public Object getEarlyBeanReference(Object bean, String beanName) {
        earlyReferences.put(beanName, bean);
        return process(bean, beanName);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (earlyReferences.remove(beanName) == bean) {
            return bean; // already processed for a circular reference, and that result is the bean
        }
        return process(bean, beanName);
    }

    private Object process(Object bean, String beanName) {
        if (excluded.contains(beanName) || ScopedProxyUtils.isScopedTarget(beanName)) {
            return bean; // a scoped bean's target: its scoped proxy (the bean the application sees) is processed
        }
        Plan plan = AopUtils.isAopProxy(bean) ? plan(bean.getClass(), AopUtils.getTargetClass(bean))
                : plans.computeIfAbsent(bean.getClass(), type -> plan(type, type));
        return switch (plan) {
            case Keep keep -> bean;
            case Wrap(Class<?> type) -> wrap && !Spies.isSpy(bean) ? wrap(type, bean, beanName) : bean;
            case Unprotected(Class<?> annotated, Class<?> subclass) -> throw unprotected(beanName, bean, annotated, subclass);
            case Several(List<Class<?>> interfaces) -> throw several(beanName, bean, interfaces);
        };
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        if (beanFactory instanceof ConfigurableListableBeanFactory listable) {
            this.beanFactory = listable;
        }
    }

    /**
     * Fails for a singleton this post-processor never saw: one that a post-processor depends on, which Spring created
     * before registering this one. Objects registered as singletons by hand (no bean definition) are not processed by
     * any post-processor and are left alone, as are a test's Mockito mocks and spies.
     */
    @Override
    public void afterSingletonsInstantiated() {
        ConfigurableListableBeanFactory factory = beanFactory;
        if (factory == null) {
            return;
        }
        for (String name : factory.getBeanDefinitionNames()) {
            if (excluded.contains(name) || ScopedProxyUtils.isScopedTarget(name)) {
                continue;
            }
            Object bean = factory.getSingleton(name);
            if (bean == null || Spies.isMock(bean)) {
                continue; // a test's @MockitoBean or @MockitoSpyBean, which is injected as it is
            }
            Plan plan = AopUtils.isAopProxy(bean) ? plan(bean.getClass(), AopUtils.getTargetClass(bean))
                    : plans.computeIfAbsent(bean.getClass(), type -> plan(type, type));
            switch (plan) {
                case Keep keep -> { }
                case Wrap(Class<?> type) -> {
                    if (wrap) {
                        throw createdEarly(name, bean, type, List.of(factory.getDependentBeans(name)));
                    }
                }
                case Unprotected(Class<?> annotated, Class<?> subclass) -> throw unprotected(name, bean, annotated, subclass);
                case Several(List<Class<?>> interfaces) -> throw several(name, bean, interfaces);
            }
        }
    }

    private static ResilienceBeanException createdEarly(String beanName, Object bean, Class<?> type,
            List<String> dependents) {
        String neededBy = dependents.isEmpty() ? "" : " It was needed by " + dependents.stream()
                .map(d -> "'" + d + "'").collect(java.util.stream.Collectors.joining(", ")) + ".";
        return new ResilienceBeanException(ErrorCode.CREATED_BEFORE_POST_PROCESSORS, "Bean '" + beanName + "' ("
                + AopUtils.getTargetClass(bean).getName() + ") implements " + type.getName() + ", which has"
                + " resilience4cata annotations, but it was created before resilience4cata's post-processor was"
                + " registered, so it is not wrapped and its policies would not run. Spring creates a bean that early"
                + " when a BeanPostProcessor or a BeanFactoryPostProcessor depends on it (it logs that the bean \"is"
                + " not eligible for getting processed by all BeanPostProcessors\")." + neededBy, "Inject it lazily"
                + " into that post-processor (ObjectProvider<" + type.getSimpleName() + "> or @Lazy), or annotate the"
                + " class instead of the interface (Spring then instantiates its generated subclass, early or not), or"
                + " list the bean in resilience4cata.spring.exclude to leave it unprotected.");
    }

    @SuppressWarnings("unchecked")
    private <T> Object wrap(Class<T> type, Object bean, String beanName) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Bean '" + beanName + "' is wrapped with " + type.getName() + Resilient.GENERATED_SUFFIX);
        }
        return Resilient.of(type, (T) bean, registry.get());
    }

    /**
     * @param beanClass the bean object's class (a JDK proxy implements the proxied interfaces)
     * @param target the class behind an AOP proxy, or {@code beanClass}
     */
    private Plan plan(Class<?> beanClass, Class<?> target) {
        for (Class<?> c = target; c != null && c != Object.class; c = c.getSuperclass()) {
            if (generated.isGenerated(c)) {
                return KEEP;
            }
            Class<?> subclass = generated.subclassOf(c);
            if (subclass != null) {
                return new Unprotected(c, subclass);
            }
        }
        Set<Class<?>> interfaces = new LinkedHashSet<>(ClassUtils.getAllInterfacesForClassAsSet(target));
        interfaces.addAll(ClassUtils.getAllInterfacesForClassAsSet(beanClass));
        List<Class<?>> annotated = new ArrayList<>();
        for (Class<?> type : interfaces) {
            if (generated.wrapperOf(type) != null) {
                annotated.add(type);
            }
        }
        if (annotated.isEmpty()) {
            return KEEP;
        }
        for (Class<?> candidate : annotated) {
            if (annotated.stream().allMatch(other -> other.isAssignableFrom(candidate))) {
                return new Wrap(candidate); // the most specific: its wrapper implements every other one
            }
        }
        return new Several(List.copyOf(annotated));
    }

    private static ResilienceBeanException unprotected(String beanName, Object bean, Class<?> annotated,
            Class<?> subclass) {
        String what = AopUtils.getTargetClass(bean) == annotated ? "an instance of " + annotated.getName()
                : "an instance of " + AopUtils.getTargetClass(bean).getName() + ", which extends " + annotated.getName();
        return new ResilienceBeanException(ErrorCode.UNPROTECTED_INSTANCE, "Bean '" + beanName + "' is " + what
                + ", which has resilience4cata annotations, but not of the generated " + subclass.getName()
                + ", so its policies would not run. It was created by a @Bean method, a supplier, or as a"
                + " subclass that is not annotated itself.", "Return new " + subclass.getSimpleName()
                + "(registry, ...) from the @Bean method (inject the ResilienceRegistry), or register the class"
                + " itself (component scanning, @Import) so that Spring instantiates " + subclass.getSimpleName()
                + "; annotate the subclass if there is one; or list the bean in resilience4cata.spring.exclude to"
                + " leave it unprotected.");
    }

    private static ResilienceBeanException several(String beanName, Object bean, List<Class<?>> interfaces) {
        List<String> names = interfaces.stream().map(Class::getName).toList();
        return new ResilienceBeanException(ErrorCode.SEVERAL_INTERFACES, "Bean '" + beanName + "' ("
                + AopUtils.getTargetClass(bean).getName() + ") implements several interfaces with resilience4cata"
                + " annotations: " + names + ". A generated wrapper implements one interface only, so injecting the"
                + " bean by another one would fail.", "Annotate the class instead of its interfaces (its generated"
                + " subclass keeps every interface), or declare one interface that extends the others and implement"
                + " that one, or list the bean in resilience4cata.spring.exclude and wrap it yourself.");
    }
}
