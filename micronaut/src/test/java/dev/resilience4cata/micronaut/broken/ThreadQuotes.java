package dev.resilience4cata.micronaut.broken;

import dev.resilience4cata.micronaut.ResilientBean;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.context.scope.ThreadLocal;

/** R4C-MN-003: a scope that Micronaut proxies. */
@ThreadLocal
@ResilientBean(Quotes.class)
@Requires(property = "test.broken", value = "R4C-MN-003")
public class ThreadQuotes implements Quotes {

    @Override
    public int quote(String sku) {
        return 1;
    }
}
