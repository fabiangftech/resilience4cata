package dev.resilience4cata.micronaut.scopes;

import dev.resilience4cata.micronaut.ResilientBean;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.http.scope.RequestScope;

/**
 * R4C-MN-003: a {@code @ResilientBean} in the request scope, which Micronaut proxies. The proxy would look up the
 * request's instance, so wrapping that instance alone would lose the policies: the startup fails instead.
 */
@RequestScope
@ResilientBean(TraceClient.class)
@Requires(property = "test.scopes", value = "resilient-request")
public class RequestTrace implements TraceClient {

    @Override
    public String trace() {
        return "trace";
    }
}
