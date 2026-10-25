package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Features from separate branches used together on an annotated class. */
class ClassCombinedTest {

    private final ClassCombinedService service =
            Resilient.create(ClassCombinedService.class, ResilienceRegistry.builder().build());

    @Test
    void aGenericFallbackOnAClassRunsAfterTheRetriesAndSelfInvocationIsIntercepted() throws IOException {
        service.script.add(new IOException("1"));
        service.script.add(new IOException("2"));
        assertThat(service.describe("a")).isEqualTo("described fallback a");
        assertThat(service.calls).isEqualTo(2);
    }

    @Test
    void aWildcardAsyncMethodOnAClassIsRetried() throws Exception {
        service.script.add(new IOException("1"));
        service.script.add("found");
        CharSequence found = service.find("b").toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(found).hasToString("found");
        assertThat(service.calls).isEqualTo(2);
    }

    @Test
    void anExhaustedRejectedResultIsThrownToTheClassFallback() throws IOException {
        service.script.add("busy");
        service.script.add("busy");
        assertThat(service.status("c")).isEqualTo("rejected after 2");
    }
}
