package dev.resilience4cata.examples.docs.timeout;

// tag::timeout-minimal[]
import dev.resilience4cata.annotation.Timeout;

public interface ReportApi {

    @Timeout("2s")
    String build(String reportId);
}
// end::timeout-minimal[]
