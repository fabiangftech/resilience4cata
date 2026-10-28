package dev.resilience4cata.examples;

import static org.assertj.core.api.Assertions.assertThat;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
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
import reactor.core.publisher.Mono;

/**
 * The {@code Mono} of a generated method is a well-behaved Reactive Streams {@link Publisher}: every
 * rule of the TCK's {@link PublisherVerification} that applies to a publisher of at most one element,
 * for a stack of policies and for a hedge, with values emitted during the subscription and later. The
 * TCK is written for TestNG; each of its rules runs here as a JUnit dynamic test ({@code setUp}, then
 * the rule), and a rule the TCK skips (it needs more than one element) is reported as aborted.
 */
class MonoPublisherTckTest {

    /** The delegate: emits at most one element, at once or a millisecond later, or fails. */
    static final class Backend implements TckClient {
        @Override
        public Mono<String> stack(long elements, boolean fail, boolean delayed) {
            return emit(elements, fail, delayed);
        }

        @Override
        public Mono<String> hedged(long elements, boolean fail, boolean delayed) {
            return emit(elements, fail, delayed);
        }

        private static Mono<String> emit(long elements, boolean fail, boolean delayed) {
            Mono<String> mono = fail ? Mono.error(new IllegalStateException("failed publisher"))
                    : elements == 0 ? Mono.empty() : Mono.just("element");
            return delayed ? mono.delaySubscription(Duration.ofMillis(1)) : mono;
        }
    }

    /** The TCK's verification of one generated method. */
    static final class Verification extends PublisherVerification<String> {
        private final TckClient client;
        private final boolean hedge;
        private final boolean delayed;

        Verification(TckClient client, boolean hedge, boolean delayed) {
            super(new TestEnvironment(1_000, 100));
            this.client = client;
            this.hedge = hedge;
            this.delayed = delayed;
        }

        @Override
        public Publisher<String> createPublisher(long elements) {
            return hedge ? client.hedged(elements, false, delayed) : client.stack(elements, false, delayed);
        }

        @Override
        public Publisher<String> createFailedPublisher() {
            return hedge ? client.hedged(1, true, delayed) : client.stack(1, true, delayed);
        }

        @Override
        public long maxElementsFromPublisher() {
            return 1;
        }
    }

    private final TckClient client = Resilient.of(TckClient.class, new Backend(), ResilienceRegistry.builder().build());

    @TestFactory
    Stream<DynamicContainer> everyRuleOfTheTck() {
        return Stream.of(
                container("policy stack, value during subscribe", false, false),
                container("policy stack, value later", false, true),
                container("hedge, value during subscribe", true, false),
                container("hedge, value later", true, true));
    }

    private DynamicContainer container(String name, boolean hedge, boolean delayed) {
        List<Method> rules = Arrays.stream(PublisherVerification.class.getMethods())
                .filter(m -> m.isAnnotationPresent(org.testng.annotations.Test.class))
                .sorted(Comparator.comparing(Method::getName))
                .toList();
        assertThat(rules).as("the TCK's rules").hasSizeGreaterThan(30);
        return DynamicContainer.dynamicContainer(name, rules.stream().map(rule -> DynamicTest.dynamicTest(rule.getName(),
                () -> run(new Verification(client, hedge, delayed), rule))));
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
