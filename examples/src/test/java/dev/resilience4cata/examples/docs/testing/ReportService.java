package dev.resilience4cata.examples.docs.testing;

import dev.resilience4cata.annotation.Timeout;

// tag::testing-timeout-interface[]
public interface ReportService {

    @Timeout("2s")
    String render(String id) throws InterruptedException;
}
// end::testing-timeout-interface[]
