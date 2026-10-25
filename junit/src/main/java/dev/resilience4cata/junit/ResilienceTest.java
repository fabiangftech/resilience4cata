package dev.resilience4cata.junit;

import dev.resilience4cata.testkit.ManualClock;
import dev.resilience4cata.testkit.ResilienceTesting;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Gives each test of the annotated class a fresh {@link ManualClock} and a
 * {@link dev.resilience4cata.ResilienceRegistry} that follows it, as parameters of the test method and of
 * its {@code @BeforeEach} and {@code @AfterEach} methods. Within one test every parameter of a type gets
 * the same instance; the next test gets new ones.
 *
 * <pre>{@code
 * @ResilienceTest
 * class QuoteServiceTest {
 *
 *     @Test
 *     void retriesWithoutWaiting(ManualClock clock, ResilienceRegistry registry) {
 *         QuoteService quotes = Resilient.of(QuoteService.class, stub, registry);
 *         quotes.quote("A-1");
 *         assertThat(clock.elapsed()).isEqualTo(Duration.ofSeconds(3));
 *     }
 * }
 * }</pre>
 *
 * <p>The registry is built the first time a test asks for it, from
 * {@link ResilienceTesting#registry(ManualClock, long)} with {@link #seed()}, then every
 * {@link ConfigureRegistry} method of the test class. Same as {@code @ExtendWith(ResilienceExtension.class)},
 * plus these settings. On a method, the settings apply to that test; the closest annotation wins: the
 * method's, then the class's (or a superclass's), then an enclosing class's for {@code @Nested} tests.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@ExtendWith(ResilienceExtension.class)
public @interface ResilienceTest {

    /**
     * The seed of the {@link java.util.Random} the registry draws jitter from, so that jittered delays are
     * the same on every run.
     *
     * @return the seed; {@link ResilienceTesting#DEFAULT_SEED} by default
     */
    long seed() default ResilienceTesting.DEFAULT_SEED;

    /**
     * The first reading of the clock, in nanoseconds; for instance close to {@code Long.MAX_VALUE} to
     * check that nothing breaks when {@code nanoTime} wraps around.
     *
     * @return the start; {@code 0} by default
     */
    long startNanos() default 0L;

    /**
     * Whether a test that passes fails anyway when its clock still has pending tasks at the end
     * (an async retry, wait or timeout the test never advanced past), checked after the
     * {@code @AfterEach} methods. Off by default.
     *
     * @return {@code true} to fail such tests
     */
    boolean failOnPendingTasks() default false;
}
