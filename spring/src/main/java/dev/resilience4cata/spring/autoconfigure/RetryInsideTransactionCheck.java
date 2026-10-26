package dev.resilience4cata.spring.autoconfigure;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Stops the startup when a transaction proxy goes around a generated subclass whose retried or hedged methods
 * are transactional (R4C-SPR-004): the proxy is outside the policies, so every attempt would run in the same
 * transaction, and a failed attempt that marks it rollback-only makes the commit of a later successful attempt
 * fail with {@code UnexpectedRollbackException}.
 *
 * <p>Not ordered, so it sees the bean after Spring's auto-proxy creator. It asks the
 * {@link TransactionAttributeSource} of the proxy's own {@link TransactionInterceptor}s, which covers Spring's
 * and Jakarta's {@code @Transactional} the way the interceptor reads them. Propagations that join or start a
 * transaction count (REQUIRED, SUPPORTS, MANDATORY, REQUIRES_NEW, NESTED); NOT_SUPPORTED and NEVER do not.
 * Registered only when spring-tx is on the class path; {@code resilience4cata.spring.allow-retry-inside-transaction=true}
 * turns it off.
 */
final class RetryInsideTransactionCheck implements BeanPostProcessor {

    private final boolean allowed;
    private final Set<String> excluded;
    private final GeneratedClasses generated = new GeneratedClasses();

    RetryInsideTransactionCheck(boolean allowed, Set<String> excluded) {
        this.allowed = allowed;
        this.excluded = Set.copyOf(excluded);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (allowed || excluded.contains(beanName) || !(bean instanceof Advised advised) || !AopUtils.isAopProxy(bean)) {
            return bean;
        }
        Class<?> target = AopUtils.getTargetClass(bean);
        Class<?> superclass = target.getSuperclass();
        if (superclass == null || generated.subclassOf(superclass) != target) {
            return bean; // not a generated subclass
        }
        List<TransactionAttributeSource> sources = new ArrayList<>();
        for (Advisor advisor : advised.getAdvisors()) {
            if (advisor.getAdvice() instanceof TransactionInterceptor interceptor
                    && interceptor.getTransactionAttributeSource() != null) {
                sources.add(interceptor.getTransactionAttributeSource());
            }
        }
        if (sources.isEmpty()) {
            return bean;
        }
        Set<String> problems = new LinkedHashSet<>();
        for (Map.Entry<Method, String> repeating : RepeatingMethods.of(target).entrySet()) {
            for (TransactionAttributeSource source : sources) {
                TransactionAttribute attribute = source.getTransactionAttribute(repeating.getKey(), target);
                if (attribute != null && joinsOrStarts(attribute.getPropagationBehavior())) {
                    problems.add(repeating.getValue() + " on " + repeating.getKey().getName() + " ("
                            + propagation(attribute.getPropagationBehavior()) + ")");
                }
            }
        }
        if (problems.isEmpty()) {
            return bean;
        }
        throw new ResilienceBeanException(ErrorCode.RETRY_INSIDE_TRANSACTION, "Bean '" + beanName + "' ("
                + superclass.getName() + ") has transactional methods that are retried or hedged: "
                + String.join(", ", problems) + ". The transaction proxy goes around the generated "
                + target.getSimpleName() + ", so every attempt would run in the same transaction, and an attempt that"
                + " fails and marks it rollback-only makes the commit of a later successful attempt fail with"
                + " UnexpectedRollbackException.", "Declare the policy on an interface the bean implements (the"
                + " interface's wrapper goes outside the transaction proxy: one transaction per attempt), or move"
                + " @Transactional to another bean that the retried method calls, or set"
                + " resilience4cata.spring.allow-retry-inside-transaction=true if one shared transaction is intended.");
    }

    private static boolean joinsOrStarts(int propagation) {
        return propagation != TransactionDefinition.PROPAGATION_NOT_SUPPORTED
                && propagation != TransactionDefinition.PROPAGATION_NEVER;
    }

    private static String propagation(int propagation) {
        return switch (propagation) {
            case TransactionDefinition.PROPAGATION_REQUIRED -> "REQUIRED";
            case TransactionDefinition.PROPAGATION_SUPPORTS -> "SUPPORTS";
            case TransactionDefinition.PROPAGATION_MANDATORY -> "MANDATORY";
            case TransactionDefinition.PROPAGATION_REQUIRES_NEW -> "REQUIRES_NEW";
            case TransactionDefinition.PROPAGATION_NESTED -> "NESTED";
            default -> "propagation " + propagation;
        };
    }
}
