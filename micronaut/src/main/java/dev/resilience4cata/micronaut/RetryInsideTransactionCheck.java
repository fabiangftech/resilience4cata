package dev.resilience4cata.micronaut;

import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops a bean that is Micronaut's AOP proxy around a generated subclass whose retried or hedged methods are
 * {@code @Transactional} (R4C-MN-005): the proxy is outside the policies, so every attempt would run in the same
 * transaction, and an attempt that fails and marks it rollback-only makes the commit of a later successful attempt
 * fail. This happens with {@code -Ar4c.inject=micronaut}, which copies {@code @Transactional} onto the generated
 * class and its overrides; an interface wrapper ({@link ResilientBean}) goes outside the bean's proxy instead (one
 * transaction per attempt).
 *
 * <p>Reads Micronaut's {@code io.micronaut.transaction.annotation.Transactional} stereotype (which
 * {@code jakarta.transaction.Transactional} maps onto) on the proxy's executable methods, by name: no dependency on
 * micronaut-data-tx. Propagations that join or start a transaction count; {@code NOT_SUPPORTED} and {@code NEVER}
 * do not. {@code resilience4cata.micronaut.allow-retry-inside-transaction=true} turns it off.
 */
final class RetryInsideTransactionCheck {

    private static final String TRANSACTIONAL = "io.micronaut.transaction.annotation.Transactional";
    private static final String INTERCEPTED = "io.micronaut.aop.Intercepted";

    private final GeneratedClasses generated;
    private final Map<Class<?>, Optional<ResilienceBeanException>> verdicts = new ConcurrentHashMap<>();

    RetryInsideTransactionCheck(GeneratedClasses generated) {
        this.generated = generated;
    }

    /** Throws if {@code bean} is a transaction proxy around retried methods of a generated subclass. */
    void check(BeanDefinition<?> definition, Object bean) {
        Optional<ResilienceBeanException> problem = verdicts.computeIfAbsent(bean.getClass(), type -> verdict(definition, type));
        if (problem.isPresent()) {
            throw problem.get();
        }
    }

    private Optional<ResilienceBeanException> verdict(BeanDefinition<?> definition, Class<?> proxy) {
        Class<?> subclass = proxy.getSuperclass();
        if (subclass == null || !generated.isGeneratedSubclass(subclass)
                || Arrays.stream(proxy.getInterfaces()).noneMatch(i -> i.getName().equals(INTERCEPTED))) {
            return Optional.empty();
        }
        Set<String> problems = new LinkedHashSet<>();
        for (Map.Entry<Method, String> repeating : RepeatingMethods.of(subclass).entrySet()) {
            Method method = repeating.getKey();
            Optional<? extends ExecutableMethod<?, Object>> executable =
                    definition.findMethod(method.getName(), method.getParameterTypes());
            if (executable.isEmpty() || !executable.get().hasStereotype(TRANSACTIONAL)) {
                continue;
            }
            String propagation = executable.get().stringValue(TRANSACTIONAL, "propagation").orElse("REQUIRED");
            if (!propagation.equals("NOT_SUPPORTED") && !propagation.equals("NEVER")) {
                problems.add(repeating.getValue() + " on " + method.getName() + " (" + propagation + ")");
            }
        }
        if (problems.isEmpty()) {
            return Optional.empty();
        }
        String annotated = subclass.getSuperclass().getName();
        return Optional.of(ErrorCode.RETRY_INSIDE_TRANSACTION.exception("Bean " + annotated + " has transactional"
                + " methods that are retried or hedged: " + String.join(", ", problems) + ". Micronaut's transaction"
                + " proxy goes around the generated " + subclass.getSimpleName() + ", so every attempt would run in the"
                + " same transaction, and an attempt that fails and marks it rollback-only makes the commit of a later"
                + " successful attempt fail.", "Declare the policy on an interface that the bean implements and"
                + " annotate the bean @ResilientBean (the wrapper goes outside the transaction proxy: one transaction"
                + " per attempt), or move @Transactional to another bean that the retried method calls, or set "
                + MicronautSettings.PREFIX + ".allow-retry-inside-transaction=true if one shared transaction is"
                + " intended."));
    }
}
