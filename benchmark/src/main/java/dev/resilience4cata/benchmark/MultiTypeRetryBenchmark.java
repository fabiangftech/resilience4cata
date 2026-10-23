package dev.resilience4cata.benchmark;

import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.Resilient;
import dev.resilience4cata.RetryConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.internal.RetryImpl;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Retry overhead with four different services, each called from its own call site, as in a real
 * application. One operation calls all four services, so divide by four for the cost of one call.
 *
 * <p>This is the realistic counterpart of {@link RetryBenchmark}, where a single decorated method
 * lets the JIT optimise both libraries as well as it possibly can. Settings are the same as in
 * {@link RetryBenchmark}: 3 attempts, no delay, no jitter, no event listeners. The
 * {@code NoSleep} variant removes Resilience4j's unconditional {@code Thread.sleep(0)}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g"})
public class MultiTypeRetryBenchmark {

    private Services.Users users;
    private Services.Orders orders;
    private Services.Payments payments;
    private Services.Shipping shipping;

    private Services.Users r4cUsers;
    private Services.Orders r4cOrders;
    private Services.Payments r4cPayments;
    private Services.Shipping r4cShipping;

    private Supplier<String> r4jUsers;
    private Supplier<String> r4jOrders;
    private Supplier<String> r4jPayments;
    private Supplier<String> r4jShipping;
    private Supplier<String> r4jUsersFailOnce;
    private Supplier<String> r4jOrdersFailOnce;
    private Supplier<String> r4jPaymentsFailOnce;
    private Supplier<String> r4jShippingFailOnce;

    /** Builds both libraries with equivalent configuration. */
    @Setup
    public void setup(BenchmarkParams params) {
        if (params.getBenchmark().endsWith("NoSleep")) {
            RetryImpl.setSleepFunction(millis -> { });
        }
        users = new Services.UsersImpl();
        orders = new Services.OrdersImpl();
        payments = new Services.PaymentsImpl();
        shipping = new Services.ShippingImpl();

        ResilienceRegistry registry = ResilienceRegistry.builder()
                .retry("bench", r -> r.maxAttempts(3).delay(Duration.ZERO)
                        .backoff(RetryConfig.Backoff.FIXED).jitter(RetryConfig.Jitter.NONE))
                .build();
        r4cUsers = Resilient.of(Services.Users.class, new Services.UsersImpl(), registry);
        r4cOrders = Resilient.of(Services.Orders.class, new Services.OrdersImpl(), registry);
        r4cPayments = Resilient.of(Services.Payments.class, new Services.PaymentsImpl(), registry);
        r4cShipping = Resilient.of(Services.Shipping.class, new Services.ShippingImpl(), registry);

        io.github.resilience4j.retry.RetryConfig config = io.github.resilience4j.retry.RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ZERO)
                .retryExceptions(Exception.class)
                .build();
        Services.UsersImpl u = new Services.UsersImpl();
        Services.OrdersImpl o = new Services.OrdersImpl();
        Services.PaymentsImpl p = new Services.PaymentsImpl();
        Services.ShippingImpl s = new Services.ShippingImpl();
        r4jUsers = Retry.decorateSupplier(Retry.of("users", config), u::call);
        r4jOrders = Retry.decorateSupplier(Retry.of("orders", config), o::call);
        r4jPayments = Retry.decorateSupplier(Retry.of("payments", config), p::call);
        r4jShipping = Retry.decorateSupplier(Retry.of("shipping", config), s::call);
        r4jUsersFailOnce = Retry.decorateSupplier(Retry.of("users-f", config), u::failOnce);
        r4jOrdersFailOnce = Retry.decorateSupplier(Retry.of("orders-f", config), o::failOnce);
        r4jPaymentsFailOnce = Retry.decorateSupplier(Retry.of("payments-f", config), p::failOnce);
        r4jShippingFailOnce = Retry.decorateSupplier(Retry.of("shipping-f", config), s::failOnce);
    }

    @Benchmark
    public void baselineSuccess(Blackhole bh) {
        bh.consume(users.call());
        bh.consume(orders.call());
        bh.consume(payments.call());
        bh.consume(shipping.call());
    }

    @Benchmark
    public void resilience4cataSuccess(Blackhole bh) {
        bh.consume(r4cUsers.call());
        bh.consume(r4cOrders.call());
        bh.consume(r4cPayments.call());
        bh.consume(r4cShipping.call());
    }

    @Benchmark
    public void resilience4jSuccess(Blackhole bh) {
        bh.consume(r4jUsers.get());
        bh.consume(r4jOrders.get());
        bh.consume(r4jPayments.get());
        bh.consume(r4jShipping.get());
    }

    @Benchmark
    public void baselineRetryOnce(Blackhole bh) {
        bh.consume(retryOnce(users));
        bh.consume(retryOnce(orders));
        bh.consume(retryOnce(payments));
        bh.consume(retryOnce(shipping));
    }

    @Benchmark
    public void resilience4cataRetryOnce(Blackhole bh) {
        bh.consume(r4cUsers.failOnce());
        bh.consume(r4cOrders.failOnce());
        bh.consume(r4cPayments.failOnce());
        bh.consume(r4cShipping.failOnce());
    }

    @Benchmark
    public void resilience4jRetryOnce(Blackhole bh) {
        r4jRetryOnce(bh);
    }

    @Benchmark
    public void resilience4jRetryOnceNoSleep(Blackhole bh) {
        r4jRetryOnce(bh);
    }

    private void r4jRetryOnce(Blackhole bh) {
        bh.consume(r4jUsersFailOnce.get());
        bh.consume(r4jOrdersFailOnce.get());
        bh.consume(r4jPaymentsFailOnce.get());
        bh.consume(r4jShippingFailOnce.get());
    }

    // Hand-written retry for the baseline: one direct call site per service.
    private static String retryOnce(Services.Users service) {
        try {
            return service.failOnce();
        } catch (RuntimeException e) {
            return service.failOnce();
        }
    }

    private static String retryOnce(Services.Orders service) {
        try {
            return service.failOnce();
        } catch (RuntimeException e) {
            return service.failOnce();
        }
    }

    private static String retryOnce(Services.Payments service) {
        try {
            return service.failOnce();
        } catch (RuntimeException e) {
            return service.failOnce();
        }
    }

    private static String retryOnce(Services.Shipping service) {
        try {
            return service.failOnce();
        } catch (RuntimeException e) {
            return service.failOnce();
        }
    }
}
