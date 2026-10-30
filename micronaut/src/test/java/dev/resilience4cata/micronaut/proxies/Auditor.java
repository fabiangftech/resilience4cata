package dev.resilience4cata.micronaut.proxies;

import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts every invocation of an {@link Audit} method it sees. */
@Singleton
@Requires(env = "proxies")
public class Auditor implements MethodInterceptor<Object, Object> {

    public final AtomicInteger invocations = new AtomicInteger();

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        invocations.incrementAndGet();
        return context.proceed();
    }
}
