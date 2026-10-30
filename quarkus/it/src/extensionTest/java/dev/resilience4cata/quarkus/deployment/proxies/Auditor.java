package dev.resilience4cata.quarkus.deployment.proxies;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts every invocation of an {@link Audit} method it sees. */
@Audit
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class Auditor {

    public static final AtomicInteger INVOCATIONS = new AtomicInteger();

    @AroundInvoke
    Object count(InvocationContext context) throws Exception {
        INVOCATIONS.incrementAndGet();
        return context.proceed();
    }
}
