package dev.resilience4cata.spring.autoconfigure;

import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

/**
 * Makes Spring instantiate the generated subclass {@code C$Resilient} wherever a bean definition names an
 * annotated class {@code C} (component scanning, {@code @Import}, {@code registerBean}): the bean class of
 * the definition is replaced, so the bean is a {@code C}, keeps its name, qualifiers, scope and injection
 * points, and its methods run their policies, calls on {@code this} included. The generated constructors
 * take the {@code ResilienceRegistry} first, which Spring injects by type, then {@code C}'s parameters with
 * their names and annotations.
 *
 * <p>Runs once, after the configuration classes are parsed; during AOT processing its effect is written
 * into the generated bean definitions, and it is left out of the optimized context. Definitions with a
 * factory method or an instance supplier cannot be changed: {@link ResilientBeansPostProcessor} reports
 * their beans (R4C-SPR-001).
 */
final class ResilientClassesPostProcessor implements BeanFactoryPostProcessor, BeanFactoryInitializationAotProcessor,
        Ordered {

    private static final Log LOG = LogFactory.getLog(ResilientClassesPostProcessor.class);
    /** In the name of the subclass that Spring generates for a {@code @Configuration} class with {@code @Bean} methods. */
    private static final String CGLIB = "$$";

    private final GeneratedClasses generated = new GeneratedClasses();
    private final Set<String> excluded;

    ResilientClassesPostProcessor(Set<String> excluded) {
        this.excluded = Set.copyOf(excluded);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        ClassLoader loader = beanFactory.getBeanClassLoader();
        for (String name : beanFactory.getBeanDefinitionNames()) {
            if (beanFactory.getBeanDefinition(name) instanceof AbstractBeanDefinition definition) {
                swap(name, definition, loader);
            }
        }
    }

    private void swap(String name, AbstractBeanDefinition definition, @Nullable ClassLoader loader) {
        String className = definition.getBeanClassName();
        if (className == null || definition.getFactoryMethodName() != null || definition.getInstanceSupplier() != null
                || definition.isAbstract() || excluded.contains(name)) {
            return;
        }
        String userClassName = className.contains(CGLIB) ? className.substring(0, className.indexOf(CGLIB)) : className;
        if (GeneratedClasses.skipped(userClassName)) {
            return;
        }
        Class<?> type;
        try {
            type = ClassUtils.forName(userClassName, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return; // Spring reports it when it creates the bean
        }
        Class<?> subclass = generated.subclassOf(type);
        if (subclass == null) {
            return;
        }
        if (!className.equals(userClassName)) {
            throw new ResilienceBeanException(ErrorCode.ANNOTATED_CONFIGURATION, "Bean '" + name + "': "
                    + type.getName() + " is a @Configuration class with @Bean methods and resilience4cata"
                    + " annotations. Spring instantiates its own subclass of such a class (" + className + "), so "
                    + subclass.getSimpleName() + " cannot be used.", "Move the annotated methods to a @Component or"
                    + " @Service class, or use @Configuration(proxyBeanMethods = false).");
        }
        definition.setBeanClass(subclass);
        shiftIndexedArguments(definition);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Bean '" + name + "' is instantiated as " + subclass.getName());
        }
    }

    /** Explicit constructor arguments by index move one place: the generated constructors take the registry first. */
    private static void shiftIndexedArguments(AbstractBeanDefinition definition) {
        if (!definition.hasConstructorArgumentValues()) {
            return;
        }
        ConstructorArgumentValues arguments = definition.getConstructorArgumentValues();
        if (arguments.getIndexedArgumentValues().isEmpty()) {
            return;
        }
        ConstructorArgumentValues shifted = new ConstructorArgumentValues();
        arguments.getIndexedArgumentValues().forEach((index, value) -> shifted.addIndexedArgumentValue(index + 1, value));
        arguments.getGenericArgumentValues().forEach(shifted::addGenericArgumentValue);
        arguments.clear();
        arguments.addArgumentValues(shifted);
    }

    /** Nothing to contribute: the swap is already in the bean definitions that AOT writes. */
    @Override
    public @Nullable BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        return null;
    }

    /** After every other bean factory post-processor that might register or change definitions. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
