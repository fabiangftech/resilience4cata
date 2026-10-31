package dev.resilience4cata.spring.autoconfigure;

import dev.resilience4cata.Resilient;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanNotOfRequiredTypeException;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * Explains an injection that failed because a bean was replaced by the generated wrapper of its annotated
 * interface, which implements only that interface: injecting the implementation class then finds a bean of
 * the wrong type (when the injection point is resolved before the bean is created) or no bean at all (after).
 * Runs before Spring Boot's own analyzers of those exceptions.
 */
final class WrappedBeanFailureAnalyzer implements FailureAnalyzer, Ordered {

    private final @Nullable BeanFactory beanFactory;
    private final @Nullable Environment environment;

    WrappedBeanFailureAnalyzer(@Nullable BeanFactory beanFactory, @Nullable Environment environment) {
        this.beanFactory = beanFactory;
        this.environment = environment;
    }

    @Override
    public @Nullable FailureAnalysis analyze(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof BeanNotOfRequiredTypeException wrongType) {
                Class<?> annotated = wrapped(wrongType.getActualType());
                if (annotated != null) {
                    return analysis(wrongType.getBeanName(), annotated, wrongType.getRequiredType(), failure);
                }
            }
            if (t instanceof NoSuchBeanDefinitionException missing && missing.getBeanType() != null) {
                FailureAnalysis analysis = wrappedCandidate(missing.getBeanType(), failure);
                if (analysis != null) {
                    return analysis;
                }
            }
        }
        return null;
    }

    /** A bean whose declared class the injection point accepts, but which was wrapped (definitions survive a failed refresh). */
    private @Nullable FailureAnalysis wrappedCandidate(Class<?> required, Throwable failure) {
        if (!(beanFactory instanceof ConfigurableListableBeanFactory factory)) {
            return null;
        }
        ResilienceSpringProperties settings = environment == null ? ResilienceSpringProperties.defaults()
                : Binder.get(environment).bindOrCreate(ResilienceSpringProperties.PREFIX, ResilienceSpringProperties.class);
        if (!settings.wrap()) {
            return null;
        }
        GeneratedClasses generated = new GeneratedClasses();
        for (String name : factory.getBeanDefinitionNames()) {
            if (settings.exclude().contains(name)) {
                continue;
            }
            Class<?> declared = declaredClass(factory, name);
            if (declared == null || declared.isInterface() || !required.isAssignableFrom(declared)) {
                continue;
            }
            for (Class<?> type : ClassUtils.getAllInterfacesForClassAsSet(declared)) {
                if (generated.wrapperOf(type) != null) {
                    return analysis(name, type, required, failure);
                }
            }
        }
        return null;
    }

    private static @Nullable Class<?> declaredClass(ConfigurableListableBeanFactory factory, String name) {
        try {
            BeanDefinition definition = factory.getMergedBeanDefinition(name);
            Class<?> declared = definition.getResolvableType().resolve();
            if (declared == null && definition.getBeanClassName() != null) {
                declared = ClassUtils.forName(definition.getBeanClassName(), factory.getBeanClassLoader());
            }
            return declared;
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    /** The annotated interface of a generated wrapper class, or {@code null}. */
    private static @Nullable Class<?> wrapped(Class<?> type) {
        for (Class<?> implemented : type.getInterfaces()) {
            if ((implemented.getName() + Resilient.GENERATED_SUFFIX).equals(type.getName())) {
                return implemented;
            }
        }
        return null;
    }

    private static FailureAnalysis analysis(String beanName, Class<?> annotated, Class<?> required, Throwable failure) {
        return new FailureAnalysis("The bean '" + beanName + "' is protected by resilience4cata with the generated "
                + annotated.getName() + Resilient.GENERATED_SUFFIX + ", which implements only the annotated interface "
                + annotated.getName() + ", but an injection point requires " + required.getName() + ".", "Inject "
                + annotated.getName() + " instead, or move the resilience4cata annotations to the class itself (Spring"
                + " then instantiates its generated subclass, which keeps the class's type), or list the bean in"
                + " resilience4cata.spring.exclude.", failure);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
