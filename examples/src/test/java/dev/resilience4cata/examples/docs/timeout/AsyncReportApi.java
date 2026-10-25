package dev.resilience4cata.examples.docs.timeout;

import dev.resilience4cata.annotation.Timeout;
import java.util.concurrent.CompletableFuture;

/** The asynchronous form of {@link ReportApi}. */
public interface AsyncReportApi {

    // tag::timeout-async[]
    @Timeout("2s")
    CompletableFuture<String> build(String reportId);
    // end::timeout-async[]
}
