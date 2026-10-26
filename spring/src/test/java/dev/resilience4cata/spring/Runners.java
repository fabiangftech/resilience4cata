package dev.resilience4cata.spring;

import dev.resilience4cata.spring.autoconfigure.ResilienceAutoConfiguration;
import dev.resilience4cata.spring.autoconfigure.ResilienceEndpointAutoConfiguration;
import dev.resilience4cata.spring.autoconfigure.ResilienceHealthAutoConfiguration;
import dev.resilience4cata.spring.autoconfigure.ResilienceMetricsAutoConfiguration;
import dev.resilience4cata.spring.fixtures.Money;
import dev.resilience4cata.testkit.ManualClock;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The context runners of the tests. */
public final class Runners {

    private Runners() {
    }

    /**
     * Every auto-configuration of the module, a {@link ManualClock} bean (so retry pauses advance it instead of
     * sleeping) and two {@link Money} beans, {@code euro} and {@code dollar}.
     */
    public static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ResilienceAutoConfiguration.class,
                        ResilienceMetricsAutoConfiguration.class, ResilienceHealthAutoConfiguration.class,
                        ResilienceEndpointAutoConfiguration.class))
                .withBean(ManualClock.class, ManualClock::new)
                .withBean("euro", Money.class, () -> new Money("EUR"))
                .withBean("dollar", Money.class, () -> new Money("USD"));
    }
}
