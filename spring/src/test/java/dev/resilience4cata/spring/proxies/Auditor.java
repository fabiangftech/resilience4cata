package dev.resilience4cata.spring.proxies;

import java.util.concurrent.atomic.AtomicInteger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/** An application's own aspect: counts every invocation it sees of an {@link Audit} method. */
@Aspect
public class Auditor {

    public final AtomicInteger invocations = new AtomicInteger();

    @Around("@annotation(dev.resilience4cata.spring.proxies.Audit)")
    public Object count(ProceedingJoinPoint call) throws Throwable {
        invocations.incrementAndGet();
        return call.proceed();
    }
}
