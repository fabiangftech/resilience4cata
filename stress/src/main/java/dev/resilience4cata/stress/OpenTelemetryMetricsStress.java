package dev.resilience4cata.stress;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;
import static org.openjdk.jcstress.annotations.Expect.FORBIDDEN;

import dev.resilience4cata.Pipeline;
import dev.resilience4cata.ResilienceRegistry;
import dev.resilience4cata.opentelemetry.OpenTelemetryMetrics;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterBuilder;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.II_Result;
import org.openjdk.jcstress.infra.results.I_Result;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link OpenTelemetryMetrics}: what {@code bindTo} and {@code close} promise when they race. Public API and a real
 * SDK with an in-memory reader, seen through a meter provider that counts the batch callbacks registered; the arbiter
 * collects once and counts the points of the breaker state gauge of the instance "shared" (one per state, so 5 for a
 * bound registry; the SDK keeps one value per attribute set even if two callbacks record it, so the points alone do
 * not show a registry bound twice: the callback count does).
 */
public final class OpenTelemetryMetricsStress {

    private OpenTelemetryMetricsStress() {}

    /** A registry with one breaker instance, "shared", and the metrics on an SDK with an in-memory reader. */
    static class Setup {
        final InMemoryMetricReader reader = InMemoryMetricReader.create();
        final SdkMeterProvider provider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        final AtomicInteger callbacks = new AtomicInteger();
        final OpenTelemetryMetrics metrics = OpenTelemetryMetrics.create(counting(provider, callbacks));
        final ResilienceRegistry registry = ResilienceRegistry.builder().build();

        Setup() {
            Pipeline.<String>builder(registry, "shared").circuitBreaker().build();
        }

        /** The provider, with every {@code batchCallback} of its meters counted. */
        static MeterProvider counting(MeterProvider provider, AtomicInteger callbacks) {
            return proxy(MeterProvider.class, (p, method, args) -> {
                Object result = invoke(provider, method, args);
                if (result instanceof MeterBuilder builder) {
                    return proxy(MeterBuilder.class, (b, m, a) -> {
                        Object built = invoke(builder, m, a);
                        if (built instanceof Meter real) {
                            return proxy(Meter.class, forward(real, callbacks));
                        }
                        return built == builder ? b : built;
                    });
                }
                return result instanceof Meter real ? proxy(Meter.class, forward(real, callbacks)) : result;
            });
        }

        private static InvocationHandler forward(Meter real, AtomicInteger callbacks) {
            return (m, method, args) -> {
                if (method.getName().equals("batchCallback")) {
                    callbacks.incrementAndGet();
                }
                return invoke(real, method, args);
            };
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private static <T> T proxy(Class<T> type, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }

        /** Points of {@code r4c.circuitbreaker.state} collected now; closes the provider. */
        int statePoints() {
            try {
                return reader.collectAllMetrics().stream().filter(m -> m.getName().equals("r4c.circuitbreaker.state"))
                        .mapToInt((MetricData m) -> m.getLongGaugeData().getPoints().size()).sum();
            } finally {
                provider.close();
            }
        }
    }

    /**
     * {@code close()} racing a first {@code bindTo}: once close has returned no gauge callback may remain, so after
     * both there are no points, whichever ran first (bindTo registers its callback under the lock only if the
     * metrics are still open).
     */
    @JCStressTest
    @Outcome(id = "0", expect = ACCEPTABLE, desc = "No gauge after close, whatever the order.")
    @Outcome(id = "5", expect = FORBIDDEN, desc = "bindTo registered its gauges after close: they outlive it.")
    @Outcome(expect = FORBIDDEN, desc = "Something else.")
    @State
    public static class BindVsClose extends Setup {
        @Actor
        public void bind() {
            metrics.bindTo(registry);
        }

        @Actor
        public void close() {
            metrics.close();
        }

        @Arbiter
        public void after(I_Result r) {
            r.r1 = statePoints();
        }
    }

    /**
     * Two threads bind the same registry: one batch callback, and one point per state. Two callbacks would take two
     * snapshots per collection and record every point twice (the SDK keeps one and logs a warning).
     */
    @JCStressTest
    @Outcome(id = "1, 5", expect = ACCEPTABLE, desc = "One callback for the registry.")
    @Outcome(id = "2, .*", expect = FORBIDDEN, desc = "The registry was bound twice.")
    @Outcome(expect = FORBIDDEN, desc = "Something else.")
    @State
    public static class ConcurrentBind extends Setup {
        @Actor
        public void bind1() {
            metrics.bindTo(registry);
        }

        @Actor
        public void bind2() {
            metrics.bindTo(registry);
        }

        @Arbiter
        public void after(II_Result r) {
            r.r1 = callbacks.get();
            r.r2 = statePoints();
        }
    }
}
