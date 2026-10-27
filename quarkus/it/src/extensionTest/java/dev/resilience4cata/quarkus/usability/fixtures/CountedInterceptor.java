package dev.resilience4cata.quarkus.usability.fixtures;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts the invocations it intercepts. */
@Counted
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class CountedInterceptor {

    public static final AtomicInteger INVOCATIONS = new AtomicInteger();

    @AroundInvoke
    Object count(InvocationContext context) throws Exception {
        INVOCATIONS.incrementAndGet();
        return context.proceed();
    }
}
