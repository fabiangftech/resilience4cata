package dev.resilience4cata.quarkus.usability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.quarkus.usability.fixtures.Flaky;
import dev.resilience4cata.quarkus.usability.fixtures.Reports;
import dev.resilience4cata.quarkus.usability.fixtures.Reports$Resilient;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * {@code @Scheduled} on a retried method of an {@code @ApplicationScoped} annotated class: one job, run on the
 * generated subclass. The build fails instead: the scheduler adds {@code @Singleton} to the subclass (its override
 * carries {@code @Scheduled} and, in the index, no scope), and the extension then copies {@code @ApplicationScoped}.
 */
@Disabled("BUG-QK-6: 'Reports$Resilient declares multiple scope type annotations: Singleton, ApplicationScoped'")
class ScheduledMethodTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Flaky.class, Reports.class, Reports$Resilient.class));

    @Inject
    Scheduler scheduler;

    @Inject
    Reports reports;

    @Test
    void theMethodIsScheduledOnce() {
        assertThat(reports).isInstanceOf(Reports$Resilient.class);
        assertThat(scheduler.getScheduledJobs()).hasSize(1);
        assertThat(scheduler.getScheduledJobs().get(0).getMethodDescription()).contains("refresh");
    }
}
