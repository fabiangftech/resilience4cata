package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.SkipException;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * The {@code Flux} of a generated method is a well-behaved Reactive Streams {@link Publisher}: every rule
 * of the TCK's {@link PublisherVerification}, those for many elements and unbounded demand included, for a
 * stack of policies and for a stream that the fallback provides after the retries gave up, with elements
 * emitted on the subscribing thread and on another one. The TCK is written for TestNG; each rule runs
 * here as a JUnit dynamic test ({@code setUp}, then the rule), and a rule the TCK skips is reported as
 * aborted.
 */
class FluxPublisherTckTest {

    /** The delegate: {@code elements} elements (endless for {@code Long.MAX_VALUE}), or a failure. */
    static final class Backend implements FluxTckClient {
        @Override
        public Flux<String> stack(long elements, boolean fail, boolean async) {
            return emit(elements, fail, async);
        }

        @Override
        public Flux<String> recovered(long elements, boolean fail, boolean async) {
            return Flux.error(new IllegalStateException("the delegate always fails"));
        }

        static Flux<String> emit(long elements, boolean fail, boolean async) {
            if (fail) {
                return Flux.error(new IllegalStateException("failed publisher"));
            }
            Flux<String> endless = Flux.generate(sink -> sink.next("element"));
            Flux<String> flux = elements == Long.MAX_VALUE ? endless : endless.take(elements);
            return async ? flux.subscribeOn(Schedulers.parallel()) : flux;
        }
    }

    /** The TCK's verification of one generated method. */
    static final class Verification extends PublisherVerification<String> {
        private final FluxTckClient client;
        private final boolean fallback;
        private final boolean async;

        Verification(FluxTckClient client, boolean fallback, boolean async) {
            super(new TestEnvironment(1_000, 100));
            this.client = client;
            this.fallback = fallback;
            this.async = async;
        }

        @Override
        public Publisher<String> createPublisher(long elements) {
            return fallback ? client.recovered(elements, false, async) : client.stack(elements, false, async);
        }

        @Override
        public Publisher<String> createFailedPublisher() {
            return fallback ? client.recovered(1, true, async) : client.stack(1, true, async);
        }
    }

    private final FluxTckClient client = Resilient.of(FluxTckClient.class, new Backend(), ResilienceRegistry.builder().build());

    @TestFactory
    Stream<DynamicContainer> everyRuleOfTheTck() {
        return Stream.of(
                container("policy stack, elements on the subscribing thread", false, false),
                container("policy stack, elements on another thread", false, true),
                container("fallback's stream, elements on the subscribing thread", true, false),
                container("fallback's stream, elements on another thread", true, true));
    }

    private DynamicContainer container(String name, boolean fallback, boolean async) {
        List<Method> rules = Arrays.stream(PublisherVerification.class.getMethods())
                .filter(m -> m.isAnnotationPresent(org.testng.annotations.Test.class))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
        assertThat(rules).as("the TCK's rules").hasSizeGreaterThan(30);
        return DynamicContainer.dynamicContainer(name, rules.stream().map(rule -> DynamicTest.dynamicTest(rule.getName(),
                () -> run(new Verification(client, fallback, async), rule))));
    }

    private static void run(Verification verification, Method rule) throws Throwable {
        verification.setUp();
        try {
            rule.invoke(verification);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof SkipException skip) {
                Assumptions.abort("skipped by the TCK: " + skip.getMessage());
            }
            throw e.getCause();
        }
    }
}
