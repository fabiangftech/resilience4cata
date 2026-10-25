package dev.resilience4cata.examples.docs.bulkhead;

// tag::bulkhead-minimal[]
import dev.resilience4cata.annotation.Bulkhead;

public interface ReportRenderer {

    @Bulkhead(maxConcurrent = 10)
    String render(String reportId);
}
// end::bulkhead-minimal[]
