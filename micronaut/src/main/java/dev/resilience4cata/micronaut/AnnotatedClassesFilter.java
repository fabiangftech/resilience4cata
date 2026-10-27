package dev.resilience4cata.micronaut;

import dev.resilience4cata.Resilient;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.context.BeanContextConfiguration;
import io.micronaut.context.BeanResolutionCustomizer;
import io.micronaut.context.Qualifier;
import io.micronaut.context.annotation.Secondary;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.AdvisedBeanType;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.inject.QualifiedBeanType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Makes the generated subclass {@code C$Resilient} of an annotated class {@code C} the bean in place of {@code C}
 * (with {@code -Ar4c.inject=micronaut} it carries {@code C}'s scope and qualifiers). Installed on every context through
 * {@code ServiceLoader}; it works when the context starts and on bean lookups, never per call.
 *
 * <ul>
 *   <li>A beans predicate leaves out {@code C}'s own definition, recognized by its name ({@code p.$C$Definition}, and
 *       {@code p.$C$Definition$…} for its AOP proxy). Beans of type {@code C} from {@code @Factory} methods (a test's
 *       {@code @MockBean} among them) and subclasses of {@code C} stay: a {@code @Replaces(C.class)} on the generated
 *       class would have removed them too.
 *   <li>When an unqualified lookup of {@code C} finds several beans, Micronaut prefers the one whose type is exactly
 *       {@code C}; a resolution customizer applies that rule to {@code C$Resilient}, so a subclass bean of {@code C}
 *       does not make {@code C} ambiguous.
 * </ul>
 *
 * <p>A beans predicate set before this one is kept; one the application sets afterwards replaces it, and an
 * annotated class is then reported at its creation (R4C-MN-004) instead of being silently doubled.
 */
public final class AnnotatedClassesFilter implements ApplicationContextConfigurer {

    private static final String DEFINITION = "$Definition";

    private final GeneratedClasses generated = new GeneratedClasses();

    /** Instantiated by {@code ServiceLoader}. */
    public AnnotatedClassesFilter() {
    }

    @Override
    public void configure(ApplicationContextBuilder builder) {
        Predicate<QualifiedBeanType<?>> keep = this::keep;
        @Nullable Predicate<QualifiedBeanType<?>> existing = null;
        BeanResolutionCustomizer customizer = BeanResolutionCustomizer.DEFAULT;
        if (builder instanceof BeanContextConfiguration configuration) {
            existing = configuration.beansPredicate();
            customizer = configuration.beanResolutionCustomizer();
        }
        builder.beansPredicate(existing == null ? keep : existing.and(keep));
        if (customizer == BeanResolutionCustomizer.DEFAULT) {
            builder.beanResolutionCustomizer(new ExactTypeOfGeneratedSubclasses());
        }
    }

    private boolean keep(QualifiedBeanType<?> bean) {
        if (!(bean instanceof BeanDefinitionReference<?> reference)) {
            return true;
        }
        String className = definedClass(reference.getBeanDefinitionName());
        return className == null || !replaced(className, reference.getClass().getClassLoader());
    }

    /**
     * Whether {@code className} has a generated subclass that is a Micronaut bean itself (compiled with
     * {@code -Ar4c.inject=micronaut}, so it has a definition). Without one the class stays, and if it is a bean,
     * R4C-MN-004 reports it at its creation.
     */
    private boolean replaced(String className, ClassLoader loader) {
        if (!generated.hasGeneratedSubclass(className, loader)) {
            return false;
        }
        String definition = ownDefinitionName(className + Resilient.GENERATED_SUFFIX);
        if (!GeneratedClasses.NATIVE_IMAGE) {
            return loader.getResource(definition.replace('.', '/') + ".class") != null;
        }
        try {
            Class.forName(definition, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** The name of the definition Micronaut compiles for the class {@code className}: {@code p.$Outer$Inner$Definition}. */
    static String ownDefinitionName(String className) {
        int dot = className.lastIndexOf('.');
        return className.substring(0, dot + 1) + "$" + className.substring(dot + 1) + DEFINITION;
    }

    /**
     * The class whose own definition Micronaut named {@code definitionName}: {@code p.Outer$Inner} for
     * {@code p.$Outer$Inner$Definition} or {@code p.$Outer$Inner$Definition$Intercepted…}. For a factory method's
     * definition it is a name with no class, which has no generated subclass either.
     */
    static @Nullable String definedClass(String definitionName) {
        int end = definitionName.indexOf(DEFINITION);
        if (end < 0) {
            return null;
        }
        int dot = definitionName.lastIndexOf('.', end);
        if (definitionName.charAt(dot + 1) != '$') {
            return null;
        }
        return definitionName.substring(0, dot + 1) + definitionName.substring(dot + 2, end);
    }

    /** Micronaut's last tie-break, exact type match, with {@code C$Resilient} counted as {@code C}. */
    private final class ExactTypeOfGeneratedSubclasses implements BeanResolutionCustomizer {

        @Override
        public <T> Optional<BeanDefinition<T>> resolveNonUniqueBean(Argument<T> beanType, @Nullable Qualifier<T> qualifier,
                Collection<BeanDefinition<T>> candidates) {
            List<BeanDefinition<T>> list = List.copyOf(candidates);
            // only where Micronaut itself would come to exact matching: no @Secondary, one order, no default
            if (list.size() < 2 || list.stream().anyMatch(c -> c.hasDeclaredStereotype(Secondary.class))
                    || list.stream().mapToInt(BeanDefinition::getOrder).distinct().count() > 1
                    || list.getFirst().getDefaultImplementation() != null
                    || list.stream().anyMatch(c -> c.getBeanType() == beanType.getType())) {
                return Optional.empty();
            }
            List<BeanDefinition<T>> exact = list.stream().filter(c -> effectiveType(c) == beanType.getType()).toList();
            return exact.size() == 1 ? Optional.of(exact.getFirst()) : Optional.empty();
        }

        private Class<?> effectiveType(BeanDefinition<?> candidate) {
            Class<?> type = candidate instanceof AdvisedBeanType<?> advised ? advised.getInterceptedType()
                    : candidate.getBeanType();
            return generated.isGeneratedSubclass(type) ? type.getSuperclass() : type;
        }
    }
}
