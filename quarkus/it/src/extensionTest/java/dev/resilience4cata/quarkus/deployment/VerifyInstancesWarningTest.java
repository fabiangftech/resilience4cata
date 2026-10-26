package dev.resilience4cata.quarkus.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import java.util.logging.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** By default an instances key that matches no instance is a warning: beans are created lazily. */
class VerifyInstancesWarningTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withEmptyApplication()
            .overrideConfigKey("resilience4cata.instances.nobody.retry.maxAttempts", "5")
            .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.WARNING.intValue()
                    && String.valueOf(record.getMessage()).contains("R4C-CFG-013"))
            .assertLogRecords(records -> assertThat(records).singleElement()
                    .satisfies(r -> assertThat(r.getMessage()).contains("nobody", "verify-instances=warn")));

    @Test
    void theApplicationStarts() {
        assertThat(true).isTrue();
    }
}
