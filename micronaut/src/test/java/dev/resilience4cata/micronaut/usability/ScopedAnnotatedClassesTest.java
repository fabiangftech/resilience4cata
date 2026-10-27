package dev.resilience4cata.micronaut.usability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.resilience4cata.CallNotPermittedException;
import dev.resilience4cata.ResilienceRegistry;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.PropertySource;
import io.micronaut.runtime.context.scope.refresh.RefreshEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Annotated classes in the scopes Micronaut users reach for,. */
class ScopedAnnotatedClassesTest {

    @Test
    void aRefreshableAnnotatedClassIsProtectedBeforeAndAfterARefresh() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "scopes", "quotes.prefix", "a"))) {
            RefreshableQuotes quotes = context.getBean(RefreshableQuotes.class);
            Failures failures = context.getBean(Failures.class);

            failures.failNext(2);
            assertThat(quotes.quote("x")).isEqualTo("a-x");
            assertThat(failures.calls()).isEqualTo(3);

            context.getEnvironment().addPropertySource(PropertySource.of("refreshed", Map.of("quotes.prefix", "b")));
            context.getEnvironment().refresh();
            context.publishEvent(new RefreshEvent());

            failures.failNext(2);
            assertThat(quotes.quote("x")).as("the new target is the generated subclass too").isEqualTo("b-x");
            assertThat(failures.calls()).isEqualTo(6);
        }
    }

    @Test
    void prototypesAreNewInstancesThatShareTheirNamedPolicy() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("test.usability", "scopes"))) {
            PrototypeQuotes first = context.getBean(PrototypeQuotes.class);
            PrototypeQuotes second = context.getBean(PrototypeQuotes.class);
            assertThat(first).isNotSameAs(second);
            assertThat(first.getClass().getSimpleName()).isEqualTo("PrototypeQuotes$Resilient");

            context.getBean(Failures.class).failNext(2);
            assertThatThrownBy(() -> first.quote("a")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> second.quote("a")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> context.getBean(PrototypeQuotes.class).quote("a"))
                    .as("one breaker, named prototype-quotes, for every instance")
                    .isInstanceOf(CallNotPermittedException.class);
            assertThat(context.getBean(ResilienceRegistry.class).circuitBreakers()).extracting(h -> h.id()).contains("prototype-quotes");
        }
    }
}
