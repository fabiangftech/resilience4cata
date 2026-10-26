package dev.resilience4cata.micronaut.fixtures;

import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts the invocations of each {@link Audited} method. */
@Singleton
@InterceptorBean(Audited.class)
public class AuditInterceptor implements MethodInterceptor<Object, Object> {

    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        calls.computeIfAbsent(context.getDeclaringType().getSimpleName() + "." + context.getMethodName(),
                k -> new AtomicInteger()).incrementAndGet();
        return context.proceed();
    }

    public int calls(String method) {
        AtomicInteger count = calls.get(method);
        return count == null ? 0 : count.get();
    }
}
